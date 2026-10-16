package com.launchcatch.billing.service;

import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.dto.PointCharged;
import com.launchcatch.billing.dto.RechargeReserveIncreased;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignFundingUpdater;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.Cause;
import com.launchcatch.campaign.contract.FundingJudgement;
import com.launchcatch.campaign.contract.Judgement;
import com.launchcatch.campaign.contract.PauseReason;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 충전 직후 재예약이다(V52 102행 (5)·51행, 배치 설계 I-03). PointChargedListener 가 커밋 뒤에 자기
 * 트랜잭션으로 부른다.
 *
 * 서빙 종료(BillingScheduleProperties.fixedFrom(), 기본 13:00) 뒤면 아무것도 하지 않는다. 다음 00:00
 * 예약이 반영한다. 대상은 이 점주의 PAUSED(NO_POINTS) 캠페인 중 집행 기간 안이고 그날 계획 행이 있는
 * 것뿐이다. 00:00 예약이 아직 돌지 않아 계획이 없는 캠페인은 건너뛴다.
 *
 * 한 트랜잭션이고 점주 계정 행을 먼저 잠근다(D-8). 캠페인 ID 오름차순으로(D-4) 잔액이 남는 대로 채운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeReservationService {

    private final Clock clock;
    private final BillingScheduleProperties schedule;
    private final CampaignQueryService campaignQuery;
    private final CampaignFundingUpdater fundingUpdater;
    private final PointAccountRepository accountRepository;
    private final PointLedgerRepository ledgerRepository;
    private final BudgetDailyPlanRepository planRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void reserve(PointCharged charged) {
        if (!LocalTime.now(clock).isBefore(schedule.fixedFrom())) {
            return;
        }
        LocalDate businessDate = LocalDate.now(clock);
        List<CampaignInfo> targets = campaignQuery
                .findByOwnerIdAndStatusIn(charged.ownerId(), List.of(CampaignStatus.PAUSED)).stream()
                .filter(campaign -> campaign.pauseReason() == PauseReason.NO_POINTS)
                .filter(campaign -> isExecutingOn(campaign, businessDate))
                .sorted(Comparator.comparing(CampaignInfo::campaignId))
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        accountRepository.lockByOwnerId(charged.ownerId());
        long remaining = balanceOf(charged.ownerId());
        LocalDateTime now = LocalDateTime.now(clock);
        for (CampaignInfo campaign : targets) {
            remaining = reserveFor(charged, businessDate, campaign, remaining, now);
        }
    }

    // 계획이 없으면 건너뛰고, 늘어난 몫이 없으면 그대로 두고 남은 잔액을 돌려준다
    private long reserveFor(PointCharged charged, LocalDate businessDate, CampaignInfo campaign,
                            long remaining, LocalDateTime now) {
        Optional<BudgetDailyPlan> maybePlan = planRepository.findByCampaignIdAndBusinessDate(
                campaign.campaignId(), businessDate);
        if (maybePlan.isEmpty()) {
            return remaining;
        }
        BudgetDailyPlan plan = maybePlan.get();
        long newReserved = Math.min(dailyBudgetOf(campaign), plan.getDailyBudget() + remaining);
        long delta = newReserved - plan.getDailyBudget();
        if (delta <= 0) {
            return remaining;
        }
        PointLedger reserve = PointLedger.rechargeReserve(
                charged.ownerId(), charged.paymentId(), campaign.campaignId(), businessDate, delta);
        int inserted = ledgerRepository.insertRechargeReserveIfAbsent(reserve.getOwnerId(), reserve.getPaymentId(),
                reserve.getCampaignId(), reserve.getBusinessDate(), reserve.getAmount(), now);
        if (inserted == 0) {
            // 같은 결제의 이벤트가 두 번 왔다. 이미 반영됐으므로 잔액도 다시 빼지 않는다
            return remaining;
        }
        plan.increaseDailyBudget(newReserved);
        eventPublisher.publishEvent(new RechargeReserveIncreased(campaign.campaignId(), businessDate, newReserved));
        if (newReserved >= plan.getUnitPrice()) {
            notifyResume(charged, businessDate, campaign, plan, newReserved);
        }
        log.info("event=RECHARGE_RESERVE_DONE businessDate={} campaignId={} paymentId={} delta={} newReserved={}",
                businessDate, campaign.campaignId(), charged.paymentId(), delta, newReserved);
        return remaining - delta;
    }

    private void notifyResume(PointCharged charged, LocalDate businessDate, CampaignInfo campaign,
                              BudgetDailyPlan plan, long newReserved) {
        long todaySpend = ledgerRepository.sumDeductForCampaignOn(campaign.campaignId(), businessDate);
        fundingUpdater.applyFundingJudgement(new FundingJudgement(campaign.campaignId(), charged.ownerId(),
                businessDate, Judgement.RESUME, Cause.RECHARGE, newReserved, plan.getUnitPrice(),
                dailyBudgetOf(campaign), todaySpend));
    }

    private static boolean isExecutingOn(CampaignInfo campaign, LocalDate businessDate) {
        return !businessDate.isBefore(campaign.startDate()) && !businessDate.isAfter(campaign.endDate());
    }

    // 서빙 대상이었던 캠페인은 하루 예산이 정해져 있다. 비어 있으면 캠페인 쪽 데이터가 어긋난 것이라 단계를 멈춘다
    private static long dailyBudgetOf(CampaignInfo campaign) {
        if (campaign.dailyBudget() == null) {
            throw new IllegalStateException("하루 예산이 없는 캠페인입니다. campaignId=" + campaign.campaignId());
        }
        return campaign.dailyBudget();
    }

    // 잔액이 음수면 0 으로 보고 ERROR 로 남긴다. 원장이 어긋났다는 신호지만 재예약을 멈출 이유는 아니다
    private long balanceOf(Long ownerId) {
        long balance = ledgerRepository.sumBalance(ownerId);
        if (balance < 0) {
            log.error("event=RECHARGE_RESERVE_NEGATIVE_BALANCE ownerId={} balance={}", ownerId, balance);
            return 0L;
        }
        return balance;
    }
}
