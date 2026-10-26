package com.launchcatch.billing.service;

import com.launchcatch.billing.contract.DeductionOutcome;
import com.launchcatch.billing.contract.DeductionRequest;
import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.cache.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
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
 * 계정 공유 잠금 뒤 마감을 검사하고 커밋까지 잠금을 유지해, 해제가 진행 중인 차감을 빠뜨리지 않게 한다.
 * 마감이 지난 영업일은 미과금으로 기록하고, 금전 처리의 실패는 대체값 없이 예외로 올린다.
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
    private final PointAccountRepository accountRepository;
    private final TransactionTemplate transactions;

    static final String UNDO_METRIC = "billing_deduct_undo_total";
    static final String NO_PLAN_METRIC = "billing_deduct_no_plan_total";
    static final String BATCH_FAILED_METRIC = "billing_deduct_batch_failed_total";

    /*
     * 점주 묶음마다 자기 트랜잭션에서 deduct 를 차례로 부른다. 묶음 안의 deduct 는 그 트랜잭션에 참여하므로
     * 한 노출이 실패하면 묶음 전체가 롤백되고, 새로 판정한 노출의 소진은 기존 롤백 되돌리기가 되돌린다.
     * 묶음마다 점주 하나라 트랜잭션 하나가 잡는 계정 잠금도 하나다. 묶음끼리 잠금 순서가 엇갈리지 않는다.
     * 실패한 묶음은 예외를 올리지 않고 그 노출들을 실패로 돌려준다. 릴레이가 다음 주기에 다시 넘기고 serveId 멱등이 중복 과금을 막는다.
     * 정산의 DB 나 Valkey 에 닿지 못하는 장애는 예외로 올린다. 모든 묶음이 같은 이유로 실패할 것이라 묶음별 실패로 돌려주면
     * 릴레이의 재시도 표만 커진다. 그 전에 커밋된 묶음은 다시 넘겨받으면 첫 판정을 돌려준다.
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
        byOwner(requests, outcomes).forEach((ownerId, group) -> {
            try {
                outcomes.addAll(transactions.execute(status -> group.stream()
                        .map(request -> DeductionOutcome.judged(request.serveId(),
                                deduct(request.serveId(), request.campaignId(), request.businessDate())))
                        .toList()));
            } catch (DataAccessResourceFailureException | QueryTimeoutException e) {
                throw e;
            } catch (RuntimeException e) {
                meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "owner_group").increment(group.size());
                log.warn("event=BILLING_DEDUCT_BATCH_GROUP_FAILED ownerId={} requestCount={} fallback=RETRY_NEXT_RELAY",
                        ownerId, group.size(), e);
                group.forEach(request -> outcomes.add(DeductionOutcome.failed(request.serveId())));
            }
        });
        return outcomes;
    }

    /*
     * 노출을 점주별로 묶는다. 계획이 있으면 계획의 점주, 없으면 캠페인의 점주다. deduct 가 쓰는 점주와 같은 출처다.
     * 캠페인 자체가 없으면 deduct 가 예외를 던질 노출이라 묶음에 넣지 않고 바로 실패로 담는다. 넣으면 같은 점주의 다른 노출까지 함께 실패한다.
     */
    private Map<Long, List<DeductionRequest>> byOwner(List<DeductionRequest> requests, List<DeductionOutcome> outcomes) {
        Map<Long, List<DeductionRequest>> groups = new TreeMap<>();
        List<DeductionRequest> withoutPlan = new ArrayList<>();
        for (DeductionRequest request : requests) {
            Optional<PlanSnapshot> plan = snapshotService.find(request.businessDate(), request.campaignId());
            if (plan.isPresent()) {
                groups.computeIfAbsent(plan.get().ownerId(), id -> new ArrayList<>()).add(request);
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
        for (DeductionRequest request : withoutPlan) {
            Long ownerId = campaignOwners.get(request.campaignId());
            if (ownerId == null) {
                meterRegistry.counter(BATCH_FAILED_METRIC, "reason", "unknown_campaign").increment();
                log.warn("event=BILLING_DEDUCT_BATCH_UNKNOWN_CAMPAIGN serveId={} campaignId={} fallback=RETRY_NEXT_RELAY",
                        request.serveId(), request.campaignId());
                outcomes.add(DeductionOutcome.failed(request.serveId()));
                continue;
            }
            groups.computeIfAbsent(ownerId, id -> new ArrayList<>()).add(request);
        }
        return groups;
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

        accountRepository.lockForShareByOwnerId(plan.ownerId())
                .orElseThrow(() -> new IllegalStateException("예산 계획의 점주 계정이 없습니다. ownerId=" + plan.ownerId()));
        LocalDateTime now = LocalDateTime.now(clock);
        if (businessDate.isBefore(now.toLocalDate())) {
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
        if (first != judged && judged != DeductionStatus.NOT_BILLED_CLOSED) {
            log.warn("event=BILLING_DEDUCT_REJUDGE_MISMATCH serveId={} first={} rejudged={} fallback=KEEP_FIRST",
                    serveId, first, judged);
        }
        return first;
    }
}
