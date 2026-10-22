package com.launchcatch.billing.service;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 00:00 예약 단계의 쓰기를 트랜잭션 하나씩으로 묶는다. DailyReservationService 가 부른다.
 *
 * 예약은 점주 하나가 한 트랜잭션이다. 수천 캠페인을 한 트랜잭션에 묶으면 그동안 점주 계정 잠금이 모두 잡혀
 * 충전 승인과 환불 승인이 기다리고, 중간에 실패하면 끝난 점주의 예약까지 되돌아간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReservationRecorder {

    private final PointAccountRepository accountRepository;
    private final PointLedgerRepository ledgerRepository;
    private final BudgetDailyPlanRepository planRepository;
    private final CampaignQueryService campaignQuery;
    private final BudgetPlanStatusService planStatus;
    private final Clock clock;

    /*
     * 한 점주의 캠페인에 잔액을 배분한다. campaigns 는 캠페인 ID 오름차순이고, 잔액이 모자라면 앞에서부터 채운다(D-4).
     * 예약액은 min(적용 하루 예산, 남은 잔액)이다. 0 이면 RESERVE 없이 예약액 0 인 계획만 만든다(V52 102행 (5)).
     *
     * 점주 계정 행을 먼저 잠근다(D-8). 충전 승인, 조정, 환불 승인이 같은 행을 잠가서 잔액을 읽고 RESERVE 를 쓰는
     * 사이에 잔액이 바뀌지 않는다.
     *
     * 계정 행이 없는 점주(충전한 적이 없다)도 잠그기 전에 행을 만든다(C-3 의 무변경 upsert). 잠글 행이 없으면
     * 이어받기로 두 실행이 겹칠 때 둘 다 "계획 없음"을 보고 같은 계획을 INSERT 해서 한쪽이 UNIQUE 위반으로 실패한다.
     * 잔액은 그대로 0 이다.
     *
     * 그날 계획이 이미 있는 캠페인은 건너뛴다. 계획과 RESERVE 가 이 트랜잭션에서 함께 커밋되므로 계획이 있으면 예약도
     * 끝난 것이다. 남은 캠페인은 그 예약이 빠진 잔액에서 이어서 배분한다.
     *
     * RESERVE 와 계획은 이미 있으면 그대로 두는 INSERT 로 쓴다(D-5). 계정 행 잠금 아래에서 계획이 없는 캠페인만 쓰므로 평소에는
     * 중복이 없지만, 잠금을 거치지 않고 같은 행을 쓰는 경로가 생겨도 UNIQUE 위반으로 단계가 멈추지 않게 한다. 값은 팩터리로
     * 만들어 검증한 뒤 넘긴다.
     *
     * 계획은 캠페인 상태와 관계없이 중단으로 만든다. 상태 변경 시각은 예약이 읽은 캠페인의 것을 옮긴다. 중단은 커밋 뒤의 상태
     * 확인(confirmStatus)이 캠페인을 다시 읽어 ACTIVE 일 때만 푼다. 예약이 읽은 상태를 그대로 쓰면 읽은 뒤 계획을 만들기 전에
     * 커밋된 중단이 사라지고(그때는 계획 행이 없어 상태 변경 수신이 아무것도 못 한다), 확인이 실패하거나 늦어지는 동안
     * 중단된 캠페인에 과금된다. 중단으로 만들면 무엇이 실패해도 결과는 "과금하지 않음" 쪽에 남는다.
     */
    @Transactional
    public void reserveForOwner(Long ownerId, List<CampaignInfo> campaigns, LocalDate businessDate, long unitPrice) {
        accountRepository.createIfAbsent(ownerId, LocalDateTime.now(clock));
        accountRepository.lockByOwnerId(ownerId);
        List<Long> campaignIds = campaigns.stream().map(CampaignInfo::campaignId).toList();
        Set<Long> planned = planRepository.findByBusinessDateAndCampaignIdIn(businessDate, campaignIds).stream()
                .map(BudgetDailyPlan::getCampaignId)
                .collect(Collectors.toSet());
        long remaining = balanceOf(ownerId);
        LocalDateTime now = LocalDateTime.now(clock);
        for (CampaignInfo campaign : campaigns) {
            if (planned.contains(campaign.campaignId())) {
                continue;
            }
            long reserved = Math.min(dailyBudgetOf(campaign), remaining);
            remaining -= reserved;
            if (reserved > 0) {
                PointLedger reserve = PointLedger.reserve(ownerId, campaign.campaignId(), businessDate, reserved);
                ledgerRepository.insertReserveIfAbsent(reserve.getOwnerId(), reserve.getCampaignId(),
                        reserve.getBusinessDate(), reserve.getAmount(), now);
            }
            BudgetDailyPlan plan = BudgetDailyPlan.create(campaign.campaignId(), ownerId, businessDate, reserved,
                    unitPrice, true, campaign.statusChangedAt());
            planRepository.insertIfAbsent(plan.getCampaignId(), plan.getOwnerId(), plan.getBusinessDate(),
                    plan.getDailyBudget(), plan.getUnitPrice(), plan.isPaused(), plan.getStatusChangedAt(), now);
        }
    }

    /*
     * 계획이 커밋된 뒤에 부른다. 캠페인을 다시 읽어 계획의 중단 표시를 캠페인의 현재 상태로 맞추고(ACTIVE 일 때만 중단 아님),
     * 바꾼 계획 수를 돌려준다.
     *
     * 계획이 커밋된 뒤에 읽으므로 계획을 만들기 전에 커밋된 중단도 보인다. 계획 INSERT 와 겹친 중단은 수신의 조건부 UPDATE 가
     * 아직 없는 행에 대해 UNIQUE 인덱스의 갭 잠금을 잡아 INSERT 가 그 커밋을 기다리거나, INSERT 가 먼저면 수신이 그 행의
     * 커밋을 기다려 직접 반영한다(REPEATABLE READ 기준). 계획에 저장된 것보다 이른 상태는 반영하지 않아서 수신과 순서가
     * 엇갈려도 낡은 상태로 덮지 않는다. campaign 행은 잠그지 않는다(F-7).
     *
     * 캠페인 조회에서 빠진 계획은 그대로 중단으로 남긴다.
     */
    @Transactional
    public int confirmStatus(List<Long> campaignIds, LocalDate businessDate) {
        int changed = 0;
        List<CampaignInfo> campaigns = campaignQuery.findAllById(campaignIds);
        if (campaigns.size() != campaignIds.size()) {
            log.warn("event=BILLING_RESERVE_STATUS_CAMPAIGN_MISSING businessDate={} requestedCount={} foundCount={}",
                    businessDate, campaignIds.size(), campaigns.size());
        }
        for (CampaignInfo campaign : campaigns) {
            boolean paused = campaign.status() != CampaignStatus.ACTIVE;
            if (planStatus.confirmStatus(campaign.campaignId(), businessDate, paused, campaign.statusChangedAt())) {
                changed++;
            }
        }
        return changed;
    }

    /*
     * 한 점주의 전날 남은 예약을 보정 RELEASE 한다. 점주 하나가 트랜잭션 하나다.
     *
     * RELEASE 도 잔액을 바꾸므로 점주 계정 행을 잠근 뒤 쓴다(D-8). 충전 승인, 조정, 환불 승인, 예약이 모두 이 행을 잠그고
     * 잔액을 읽는다. 잠그지 않으면 환불 승인이 잠금 뒤 잡은 스냅샷(REPEATABLE READ, 첫 일반 SELECT 에서 잡힌다)에 그 사이
     * 커밋된 RELEASE 가 빠져 잔액을 작게 읽고, 환불할 수 있는 요청을 BILLING-013 으로 거절한다. 계정 행이 없으면 예약과
     * 같이 먼저 만든다.
     *
     * 모든 점주를 한 트랜잭션에 묶지 않는다. 그러면 RELEASE 가 있는 점주 전원의 계정 행을 커밋까지 쥐어 그동안 충전과
     * 환불이 모두 멈춘다. 한 점주가 실패하면 예외가 올라가 그날 예약은 시작하지 않고, 재실행은 끝난 점주를 건너뛴다.
     *
     * 이미 RELEASE 가 있는 캠페인은 그대로 둔다(무변경 upsert). 부른 쪽이 RELEASE 가 없는 캠페인만 골라 넘기지만, 그 조회와
     * 이 쓰기 사이에 이어받은 다른 실행이 먼저 쓸 수 있다.
     */
    @Transactional
    public void releaseForOwner(Long ownerId, List<PointLedger> releases) {
        for (PointLedger release : releases) {
            if (release.getEntryType() != EntryType.RELEASE) {
                throw new IllegalArgumentException("보정 해제에는 RELEASE 만 넘긴다: " + release.getEntryType());
            }
            if (!ownerId.equals(release.getOwnerId())) {
                throw new IllegalArgumentException("다른 점주의 RELEASE 다. ownerId=" + ownerId
                        + " release.ownerId=" + release.getOwnerId());
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        accountRepository.createIfAbsent(ownerId, now);
        accountRepository.lockByOwnerId(ownerId);
        for (PointLedger release : releases) {
            ledgerRepository.insertReleaseIfAbsent(release.getOwnerId(), release.getCampaignId(),
                    release.getBusinessDate(), release.getAmount(), now);
        }
    }

    /*
     * 잠금 뒤에 최신 계획과 소진을 다시 읽어 남은 예약만 해제한다. 충전 재예약이 계획을 늘린 뒤에도
     * 이전 계획으로 부족하게 해제하지 않도록 계산과 기록을 같은 점주 트랜잭션에 둔다.
     */
    @Transactional
    public void releaseForOwner(Long ownerId, LocalDate businessDate, List<Long> campaignIds) {
        LocalDateTime now = LocalDateTime.now(clock);
        accountRepository.createIfAbsent(ownerId, now);
        accountRepository.lockByOwnerId(ownerId);
        List<BudgetDailyPlan> plans = planRepository.findByBusinessDateAndCampaignIdIn(businessDate, campaignIds);
        Set<Long> released = ledgerRepository.findReleasedCampaignIds(Set.of(ownerId), businessDate).stream()
                .collect(Collectors.toSet());
        Map<Long, Long> spends = ledgerRepository.sumDeductForCampaignsOn(campaignIds, businessDate).stream()
                .collect(Collectors.toMap(CampaignSpend::campaignId, CampaignSpend::spend));
        for (BudgetDailyPlan plan : plans) {
            if (!ownerId.equals(plan.getOwnerId()) || released.contains(plan.getCampaignId())) {
                continue;
            }
            long left = plan.getDailyBudget() - spends.getOrDefault(plan.getCampaignId(), 0L);
            if (left > 0) {
                ledgerRepository.insertReleaseIfAbsent(ownerId, plan.getCampaignId(), businessDate, left, now);
            }
        }
    }

    /*
     * 잔액이 음수면 0 으로 보고 ERROR 로 남긴다. 원장이 어긋났다는 신호지만, 예외로 멈추면 뒤 순서 점주의 예약까지
     * 그날 하루 막힌다.
     */
    private long balanceOf(Long ownerId) {
        long balance = ledgerRepository.sumBalance(ownerId);
        if (balance < 0) {
            log.error("event=BILLING_RESERVE_NEGATIVE_BALANCE ownerId={} balance={}", ownerId, balance);
            return 0L;
        }
        return balance;
    }

    // 서빙 대상 캠페인은 하루 예산이 정해져 있다. 비어 있으면 캠페인 쪽 데이터가 어긋난 것이라 단계를 멈춘다
    private long dailyBudgetOf(CampaignInfo campaign) {
        if (campaign.dailyBudget() == null) {
            throw new IllegalStateException("하루 예산이 없는 서빙 대상 캠페인입니다. campaignId=" + campaign.campaignId());
        }
        return campaign.dailyBudget();
    }
}
