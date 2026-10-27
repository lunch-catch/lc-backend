package com.lunchcatch.billing.service;

import com.lunchcatch.billing.contract.DeductionOutcome;
import com.lunchcatch.billing.contract.DeductionRequest;
import com.lunchcatch.billing.contract.DeductionStatus;
import com.lunchcatch.billing.contract.PointDeductor;
import com.lunchcatch.billing.dto.PlanSnapshot;
import com.lunchcatch.billing.cache.DailySpendCounter;
import com.lunchcatch.billing.cache.DeductOutcome;
import com.lunchcatch.billing.cache.SpendDeduction;
import com.lunchcatch.billing.entity.BudgetDailyPlan;
import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.repository.DeductionBatchRepository;
import com.lunchcatch.billing.repository.DeductionBatchRepository.DeductRow;
import com.lunchcatch.billing.repository.DeductionBatchRepository.ResultRow;
import com.lunchcatch.billing.repository.DeductionBatchRepository.StoredResult;
import com.lunchcatch.billing.repository.DeductionResultRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.ops.contract.PlatformSettingQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 유효 노출의 과금을 판정하고 기록한다(I-01). 1건은 deduct, 여러 건은 deductAll 이 받는다.
 *
 * 순서는 ① 그날 계획의 점주와 단가 ② 당일 소진 해시에서 판정 ③ 판정 기록 ④ BILLED 면 원장 DEDUCT 다.
 * 그날 계획이 없으면 NOT_BILLED_NO_PLAN 판정만 남긴다.
 * 해시가 없으면 DB 정본으로 한 번 만들고 다시 판정한다. 그래도 없으면 예외다.
 * 판정 기록과 원장은 무변경 갱신 INSERT 라서 같은 serveId 의 재호출이 예외 없이 지나가고, 판정은 Valkey 가
 * 첫 결과를 돌려준다. 잔액은 읽지 않는다.
 *
 * Valkey 는 DB 트랜잭션에 묶이지 않아서 판정이 소진을 올린 뒤 트랜잭션이 롤백되면 소진만 앞서간다(L-1).
 * 그래서 이번 호출이 새로 판정했으면 롤백 직후 그 판정을 되돌린다(처리 표시 삭제, BILLED 였으면 소진 감소).
 * 되돌리기가 실패하거나 프로세스가 죽으면 소진이 원장보다 큰 채로 그날 끝까지 남고, 다음 날 00:00 대사가 기록한다.
 *
 * 호출자 트랜잭션이 있으면 참여하고 없으면 정산 트랜잭션을 시작한다.
 * 마감은 날짜가 아니라 그 계획의 해제 여부로 본다(#1049). 그날 계획 행을 공유 잠금으로 읽어 해제 시각을 검사하고
 * 커밋까지 잠금을 유지한다. 해제는 같은 행을 배타 잠금하므로 해제 전에 승인한 차감을 빠뜨리지 않는다.
 * 해제가 끝난 계획은 미과금으로 기록하고, 금전 처리의 실패는 대체값 없이 예외로 올린다.
 * 점주 계정 행은 잠그지 않는다. 사용 가능한 잔액은 DEDUCT 를 세지 않아 충전, 조정, 환불과 순서를 맞출 필요가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointDeductorImpl implements PointDeductor {

    private final DailyPlanSnapshotService snapshotService;
    private final SpendHashRebuildService rebuildService;
    private final DailySpendCounter spendCounter;
    private final DeductionResultRepository deductionResultRepository;
    private final PointLedgerRepository ledgerRepository;
    private final CampaignQueryService campaignQuery;
    private final PlatformSettingQueryService platformSetting;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final BudgetDailyPlanRepository planRepository;
    private final DeductionBatchRepository batchRepository;
    private final TransactionTemplate transactions;

    static final String UNDO_METRIC = "billing_deduct_undo_total";
    static final String NO_PLAN_METRIC = "billing_deduct_no_plan_total";
    static final String BATCH_FAILED_METRIC = "billing_deduct_batch_failed_total";
    static final String BATCH_FALLBACK_METRIC = "billing_deduct_batch_fallback_total";

    // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
    @PostConstruct
    void registerCounters() {
        meterRegistry.counter(UNDO_METRIC, "result", "ok");
        meterRegistry.counter(UNDO_METRIC, "result", "failed");
        meterRegistry.counter(NO_PLAN_METRIC);
        meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "owner_group");
        meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "unknown_campaign");
        meterRegistry.counter(BATCH_FALLBACK_METRIC);
    }

    /*
     * 묶음 전체를 한 트랜잭션에서 판정하고 기록한다(#1049). 마감 확인, 소진 판정, 판정 기록, 원장을 노출마다가 아니라 묶음마다 한 번씩 한다.
     * 한 트랜잭션이 실패하면 그 묶음만 점주별 트랜잭션으로 다시 처리한다. 한 노출의 실패가 다른 점주의 과금까지 되돌리지 않게 한다.
     * 점주별로도 실패한 묶음은 예외를 올리지 않고 그 노출들을 실패로 돌려준다. 릴레이가 다음 주기에 다시 넘기고 serveId 멱등이 중복 과금을 막는다.
     * 정산의 DB 나 Valkey 에 닿지 못하는 장애는 예외로 올린다. 트랜잭션을 열지 못하는 것(커넥션 풀 고갈)도 같다. 모든 묶음이 같은 이유로 실패할 것이라
     * 묶음별 실패로 돌려주면 점주마다 커넥션 대기를 되풀이하고 릴레이가 노출마다 시도 횟수를 올려 상한에 금방 닿는다.
     * 그 전에 커밋된 묶음은 다시 넘겨받으면 첫 판정을 돌려준다.
     */
    @Override
    public List<DeductionOutcome> deductAll(List<DeductionRequest> requests) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("묶음 차감은 트랜잭션 밖에서 부릅니다. 점주별 트랜잭션이 호출자 트랜잭션에 섞입니다");
        }
        if (requests.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "묶음 차감은 한 번에 " + MAX_BATCH_SIZE + "건까지 받습니다. requestCount=" + requests.size());
        }
        List<DeductionOutcome> outcomes = new ArrayList<>();
        Map<Long, List<Charge>> groups = byOwner(requests, outcomes);
        if (groups.isEmpty()) {
            return outcomes;
        }
        List<Charge> charges = groups.values().stream().flatMap(List::stream).toList();
        try {
            outcomes.addAll(transactions.execute(status -> judgeAll(charges)));
            return outcomes;
        } catch (DataAccessResourceFailureException | QueryTimeoutException | CannotCreateTransactionException e) {
            throw e;
        } catch (RuntimeException e) {
            meterRegistry.counter(BATCH_FALLBACK_METRIC).increment();
            log.warn("event=BILLING_DEDUCT_BATCH_FALLBACK requestCount={} fallback=PER_OWNER", charges.size(), e);
        }
        groups.forEach((ownerId, group) -> {
            try {
                outcomes.addAll(transactions.execute(status -> judgeAll(group)));
            } catch (DataAccessResourceFailureException | QueryTimeoutException | CannotCreateTransactionException e) {
                throw e;
            } catch (RuntimeException e) {
                meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "owner_group").increment(group.size());
                log.warn("event=BILLING_DEDUCT_BATCH_GROUP_FAILED ownerId={} requestCount={} fallback=RETRY_NEXT_RELAY",
                        ownerId, group.size(), e);
                group.forEach(charge -> outcomes.add(DeductionOutcome.failed(charge.request().serveId())));
            }
        });
        return outcomes;
    }

    /*
     * 노출을 점주별로 묶는다. 계획이 있으면 계획의 점주와 단가, 없으면 캠페인의 점주와 운영 설정의 단가다. deduct 가 쓰는 것과 같은 출처다.
     * 캠페인 자체가 없으면 deduct 가 예외를 던질 노출이라 묶음에 넣지 않고 바로 실패로 담는다. 넣으면 같은 묶음의 다른 노출까지 함께 실패한다.
     */
    private Map<Long, List<Charge>> byOwner(List<DeductionRequest> requests, List<DeductionOutcome> outcomes) {
        Map<Long, List<Charge>> groups = new TreeMap<>();
        List<DeductionRequest> withoutPlan = new ArrayList<>();
        for (DeductionRequest request : requests) {
            Optional<PlanSnapshot> plan = snapshotService.find(request.businessDate(), request.campaignId());
            if (plan.isPresent()) {
                groups.computeIfAbsent(plan.get().ownerId(), id -> new ArrayList<>())
                        .add(new Charge(request, plan.get().ownerId(), plan.get().unitPrice(), true));
            } else {
                withoutPlan.add(request);
            }
        }
        if (withoutPlan.isEmpty()) {
            return groups;
        }
        Map<Long, Long> campaignOwners = campaignQuery.findAllById(
                        withoutPlan.stream().map(DeductionRequest::campaignId).distinct().toList()).stream()
                .collect(Collectors.toMap(CampaignInfo::campaignId, CampaignInfo::ownerId));
        long unitPrice = platformSetting.getImpressionUnitPrice();
        for (DeductionRequest request : withoutPlan) {
            Long ownerId = campaignOwners.get(request.campaignId());
            if (ownerId == null) {
                meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "unknown_campaign").increment();
                log.warn("event=BILLING_DEDUCT_BATCH_UNKNOWN_CAMPAIGN serveId={} campaignId={} fallback=RETRY_NEXT_RELAY",
                        request.serveId(), request.campaignId());
                outcomes.add(DeductionOutcome.failed(request.serveId()));
                continue;
            }
            groups.computeIfAbsent(ownerId, id -> new ArrayList<>()).add(new Charge(request, ownerId, unitPrice, false));
        }
        return groups;
    }

    /*
     * 트랜잭션 안에서 노출들을 판정하고 기록한다. 노출마다 deduct 와 같은 규칙이다.
     * 같은 serveId 가 묶음에 둘 이상이면 한 번만 판정하고 같은 결과를 돌려준다.
     */
    private List<DeductionOutcome> judgeAll(List<Charge> charges) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDate today = now.toLocalDate();
        Map<UUID, Charge> unique = new LinkedHashMap<>();
        charges.forEach(charge -> unique.putIfAbsent(charge.request().serveId(), charge));

        Set<PlanKey> released = releasedPlans(unique.values(), today);
        Map<UUID, DeductionStatus> judged = new HashMap<>();
        List<Charge> open = new ArrayList<>();
        for (Charge charge : unique.values()) {
            LocalDate businessDate = charge.request().businessDate();
            if (!charge.planned()) {
                DeductionStatus status = businessDate.isBefore(today)
                        ? DeductionStatus.NOT_BILLED_CLOSED : DeductionStatus.NOT_BILLED_NO_PLAN;
                if (status == DeductionStatus.NOT_BILLED_NO_PLAN) {
                    meterRegistry.counter(NO_PLAN_METRIC).increment();
                }
                judged.put(charge.request().serveId(), status);
            } else if (businessDate.isBefore(today.minusDays(1)) || released.contains(PlanKey.of(charge.request()))) {
                judged.put(charge.request().serveId(), DeductionStatus.NOT_BILLED_CLOSED);
            } else {
                open.add(charge);
            }
        }
        judged.putAll(countAll(open));

        Map<UUID, StoredResult> stored = batchRepository.insertResults(unique.values().stream()
                .map(charge -> new ResultRow(charge.request().serveId(), charge.request().campaignId(), charge.ownerId(),
                        charge.request().businessDate(), judged.get(charge.request().serveId()), charge.unitPrice()))
                .toList(), now);
        List<DeductRow> deducts = new ArrayList<>();
        Map<UUID, DeductionStatus> finals = new HashMap<>();
        for (Charge charge : unique.values()) {
            UUID serveId = charge.request().serveId();
            StoredResult result = stored.get(serveId);
            if (result == null) {
                throw new IllegalStateException("판정 행을 넣었는데 읽을 수 없습니다. serveId=" + serveId);
            }
            if (result.inserted() && result.status() == DeductionStatus.BILLED) {
                deducts.add(new DeductRow(serveId, charge.request().campaignId(), charge.ownerId(), charge.unitPrice()));
            }
            if (!result.inserted()) {
                warnIfRejudged(serveId, result.status(), judged.get(serveId));
            }
            finals.put(serveId, result.status());
        }
        if (!deducts.isEmpty()) {
            batchRepository.insertDeducts(deducts, now);
        }
        return charges.stream()
                .map(charge -> DeductionOutcome.judged(charge.request().serveId(), finals.get(charge.request().serveId())))
                .toList();
    }

    /*
     * 계획이 있는 노출의 그날 계획 행을 한 문장에서 공유 잠금으로 읽고 해제가 끝난 계획을 돌려준다.
     * 어제보다 앞선 영업일은 잠그지 않는다. 계획 스냅샷은 있는데 계획 행이 없으면 예외다.
     */
    private Set<PlanKey> releasedPlans(Collection<Charge> charges, LocalDate today) {
        Set<PlanKey> keys = charges.stream()
                .filter(Charge::planned)
                .map(charge -> PlanKey.of(charge.request()))
                .filter(key -> !key.businessDate().isBefore(today.minusDays(1)))
                .collect(Collectors.toSet());
        if (keys.isEmpty()) {
            return Set.of();
        }
        Map<PlanKey, BudgetDailyPlan> plans = planRepository.findAllForShareIn(
                        keys.stream().map(PlanKey::businessDate).collect(Collectors.toSet()),
                        keys.stream().map(PlanKey::campaignId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(plan -> new PlanKey(plan.getCampaignId(), plan.getBusinessDate()), plan -> plan));
        Set<PlanKey> released = new HashSet<>();
        for (PlanKey key : keys) {
            BudgetDailyPlan plan = plans.get(key);
            if (plan == null) {
                throw new IllegalStateException("계획 스냅샷은 있는데 계획 행이 없습니다. campaignId=" + key.campaignId()
                        + ", businessDate=" + key.businessDate());
            }
            if (plan.getReleasedAt() != null) {
                released.add(key);
            }
        }
        return released;
    }

    /*
     * 마감 전 노출을 당일 소진 해시에서 한 번에 판정한다. 해시가 없는 캠페인은 DB 정본으로 만든 뒤 그 노출만 한 번 더 보낸다.
     * 새로 판정한 노출은 트랜잭션이 롤백되면 한 번에 되돌린다.
     */
    private Map<UUID, DeductionStatus> countAll(List<Charge> charges) {
        Map<UUID, DeductionStatus> judged = new HashMap<>();
        if (charges.isEmpty()) {
            return judged;
        }
        List<SpendDeduction> fresh = new ArrayList<>();
        undoAllOnRollback(fresh);
        List<SpendDeduction> missing = judge(charges.stream().map(PointDeductorImpl::deduction).toList(), judged, fresh);
        if (missing.isEmpty()) {
            return judged;
        }
        missing.stream().map(d -> new PlanKey(d.campaignId(), d.businessDate())).distinct()
                .forEach(key -> rebuildService.rebuildIfAbsent(key.businessDate(), key.campaignId()));
        List<SpendDeduction> stillMissing = judge(missing, judged, fresh);
        if (!stillMissing.isEmpty()) {
            SpendDeduction first = stillMissing.get(0);
            throw new IllegalStateException("재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + first.campaignId()
                    + ", businessDate=" + first.businessDate());
        }
        return judged;
    }

    // 판정한 노출을 judged 에, 새로 판정한 노출을 fresh 에 담고 해시가 없던 노출을 돌려준다
    private List<SpendDeduction> judge(List<SpendDeduction> deductions, Map<UUID, DeductionStatus> judged,
                                       List<SpendDeduction> fresh) {
        List<DeductOutcome> results = spendCounter.deductAll(deductions);
        List<SpendDeduction> missing = new ArrayList<>();
        for (int i = 0; i < deductions.size(); i++) {
            DeductOutcome outcome = results.get(i);
            SpendDeduction deduction = deductions.get(i);
            if (outcome.isHashMissing()) {
                missing.add(deduction);
                continue;
            }
            judged.put(deduction.serveId(), outcome.status());
            if (!outcome.replayed()) {
                fresh.add(deduction);
            }
        }
        return missing;
    }

    private static SpendDeduction deduction(Charge charge) {
        DeductionRequest request = charge.request();
        return new SpendDeduction(request.businessDate(), request.campaignId(), request.serveId(), charge.unitPrice());
    }

    // undoOnRollback 의 묶음판이다. 등록한 뒤 fresh 에 담긴 노출까지 되돌린다
    private void undoAllOnRollback(List<SpendDeduction> fresh) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                if (completionStatus != STATUS_ROLLED_BACK || fresh.isEmpty()) {
                    return;
                }
                try {
                    spendCounter.undoAll(fresh);
                    meterRegistry.counter(UNDO_METRIC, "result", "ok").increment(fresh.size());
                } catch (RuntimeException e) {
                    meterRegistry.counter(UNDO_METRIC, "result", "failed").increment(fresh.size());
                    log.warn("event=BILLING_DEDUCT_UNDO_FAILED requestCount={} fallback=LEAVE_FOR_RECON", fresh.size(), e);
                }
            }
        });
    }

    // 계획이 있으면 planned 다. ownerId 와 unitPrice 는 계획에서, 없으면 캠페인과 운영 설정에서 읽은 값이다
    private record Charge(DeductionRequest request, Long ownerId, long unitPrice, boolean planned) {
    }

    private record PlanKey(Long campaignId, LocalDate businessDate) {

        static PlanKey of(DeductionRequest request) {
            return new PlanKey(request.campaignId(), request.businessDate());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public DeductionStatus deduct(UUID serveId, Long campaignId, LocalDate businessDate) {
        Optional<PlanSnapshot> found = snapshotService.find(businessDate, campaignId);
        if (found.isEmpty()) {
            return recordWithoutPlan(serveId, campaignId, businessDate,
                    businessDate.isBefore(LocalDate.now(clock))
                            ? DeductionStatus.NOT_BILLED_CLOSED : DeductionStatus.NOT_BILLED_NO_PLAN);
        }
        PlanSnapshot plan = found.get();

        LocalDateTime now = LocalDateTime.now(clock);
        if (isClosed(campaignId, businessDate, now.toLocalDate())) {
            int inserted = deductionResultRepository.insertIfAbsent(serveId, campaignId, plan.ownerId(), businessDate,
                    DeductionStatus.NOT_BILLED_CLOSED, plan.unitPrice(), now);
            return inserted == 1 ? DeductionStatus.NOT_BILLED_CLOSED
                    : firstStatus(serveId, DeductionStatus.NOT_BILLED_CLOSED);
        }

        DeductOutcome outcome = spendCounter.deduct(businessDate, campaignId, serveId, plan.unitPrice());
        if (outcome.isHashMissing()) {
            rebuildService.rebuildIfAbsent(businessDate, campaignId);
            outcome = spendCounter.deduct(businessDate, campaignId, serveId, plan.unitPrice());
            if (outcome.isHashMissing()) {
                throw new IllegalStateException(
                        "재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        DeductionStatus status = outcome.status();
        if (!outcome.replayed()) {
            undoOnRollback(businessDate, campaignId, serveId, plan.unitPrice());
        }
        int inserted = deductionResultRepository.insertIfAbsent(
                serveId, campaignId, plan.ownerId(), businessDate, status, plan.unitPrice(), now);
        if (inserted != 1) {
            return firstStatus(serveId, status);
        }

        if (status == DeductionStatus.BILLED) {
            ledgerRepository.insertDeductIfAbsent(plan.ownerId(), campaignId, serveId, -plan.unitPrice(), now);
        }
        return status;
    }

    /*
     * 그 계획의 예약이 해제됐으면 마감이다. 계획 행을 공유 잠금으로 읽어 커밋까지 해제를 기다리게 한다.
     * 어제보다 앞선 영업일은 계획을 잠그지 않고 마감으로 본다. 해제는 다음 날 00:00 에 끝나고 릴레이는 어제 이후 노출만 넘긴다.
     */
    private boolean isClosed(Long campaignId, LocalDate businessDate, LocalDate today) {
        if (businessDate.isBefore(today.minusDays(1))) {
            return true;
        }
        BudgetDailyPlan plan = planRepository.findForShare(campaignId, businessDate)
                .orElseThrow(() -> new IllegalStateException(
                        "계획 스냅샷은 있는데 계획 행이 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate));
        return plan.getReleasedAt() != null;
    }

    /*
     * 계획이 없으면 마감 여부에 따라 NOT_BILLED_CLOSED 또는 NOT_BILLED_NO_PLAN 을 기록한다.
     * 행을 남겨야 광고 서빙의 유효 노출 수가 판정 합과 맞는다.
     * 계획이 없으니 점주는 캠페인에서, 단가는 운영 설정에서 읽는다. 과금하지 않으므로 Valkey 와 원장은 건드리지 않는다.
     */
    private DeductionStatus recordWithoutPlan(UUID serveId, Long campaignId, LocalDate businessDate,
                                              DeductionStatus status) {
        Long ownerId = campaignQuery.findAllById(List.of(campaignId)).stream()
                .findFirst()
                .map(CampaignInfo::ownerId)
                .orElseThrow(() -> new IllegalStateException("노출한 캠페인이 없습니다. campaignId=" + campaignId));
        long unitPrice = platformSetting.getImpressionUnitPrice();
        if (status == DeductionStatus.NOT_BILLED_NO_PLAN) {
            meterRegistry.counter(NO_PLAN_METRIC).increment();
            log.debug("event=BILLING_DEDUCT_NO_PLAN campaignId={} businessDate={}", campaignId, businessDate);
        }
        int inserted = deductionResultRepository.insertIfAbsent(serveId, campaignId, ownerId, businessDate,
                status, unitPrice, LocalDateTime.now(clock));
        if (inserted != 1) {
            return firstStatus(serveId, status);
        }
        return status;
    }

    /*
     * 이 트랜잭션이 롤백으로 끝나면 이번 호출이 새로 남긴 판정을 되돌린다. 커밋이면 두고, 결과를 알 수 없으면(UNKNOWN)
     * 커밋됐을 수 있으니 되돌리지 않는다. 되돌리기 실패는 노출 저장의 결과를 바꾸지 못하므로 예외를 올리지 않고
     * 기록한다. 소진이 원장보다 큰 채로 남으면 안전한 쪽의 오차이고 다음 날 00:00 대사가 기록한다.
     */
    private void undoOnRollback(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                if (completionStatus != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    spendCounter.undo(businessDate, campaignId, serveId, unitPrice);
                    meterRegistry.counter(UNDO_METRIC, "result", "ok").increment();
                } catch (RuntimeException e) {
                    meterRegistry.counter(UNDO_METRIC, "result", "failed").increment();
                    log.warn("event=BILLING_DEDUCT_UNDO_FAILED serveId={} campaignId={} fallback=LEAVE_FOR_RECON",
                            serveId, campaignId, e);
                }
            }
        });
    }

    /*
     * 같은 serveId 의 판정 행이 이미 있으면 그 행의 판정이 정본이다. 처리 표시(2일)가 사라진 뒤 도착한 재호출은 Lua 가
     * 현재 예산으로 다시 판정하므로 처음 판정과 다를 수 있다. 계약은 처음 판정을 약속하고 원장은 처음 판정이
     * BILLED 일 때 같은 트랜잭션에서 이미 남았으므로, 저장된 판정을 돌려주고 원장은 쓰지 않는다.
     * 다시 판정하면서 올라간 카운터는 되돌리지 않는다. 다음 날 00:00 대사가 불일치로 기록한다.
     */
    private DeductionStatus firstStatus(UUID serveId, DeductionStatus judged) {
        DeductionStatus first = deductionResultRepository.findFirstStatus(serveId)
                .orElseThrow(() -> new IllegalStateException("판정 행이 있다고 했는데 읽을 수 없습니다. serveId=" + serveId));
        warnIfRejudged(serveId, first, judged);
        return first;
    }

    private void warnIfRejudged(UUID serveId, DeductionStatus first, DeductionStatus judged) {
        if (first != judged && judged != DeductionStatus.NOT_BILLED_CLOSED) {
            log.warn("event=BILLING_DEDUCT_REJUDGE_MISMATCH serveId={} first={} rejudged={} fallback=KEEP_FIRST",
                    serveId, first, judged);
        }
    }
}
