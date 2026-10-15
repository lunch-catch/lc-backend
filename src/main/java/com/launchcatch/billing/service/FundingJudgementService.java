package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignFundingUpdater;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.Cause;
import com.launchcatch.campaign.contract.FundingJudgement;
import com.launchcatch.campaign.contract.Judgement;
import com.launchcatch.campaign.contract.PauseReason;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * 00:00 잔액 부족 판정(V52 102행 (6), 배치 설계 I-02 「판정 통보」)이다. 그날 계획의 예약액을 단가와 견주어 판정하고
 * 캠페인에 직접 통보한다. 직접 호출이라 판정이 유실되지 않는다(검증 시나리오 27행).
 *
 * 대상은 ACTIVE 와 PAUSED(NO_POINTS) 다. PAUSED(OWNER) 는 점주가 재개할 때 따로 판단한다(48행).
 * 예약액이 단가보다 작으면 NO_POINTS 다. 아니면 ACTIVE 는 OK, PAUSED(NO_POINTS) 는 RESUME 이다.
 *
 * 트랜잭션을 걸지 않는다. 캠페인 구현이 캠페인마다 자기 트랜잭션을 열고, 거기서 발행한 상태 변경 이벤트를 정산의
 * 리스너(I-07)가 같은 트랜잭션에서 받아 계획 행의 중단 표시를 바꾼다. 여기서 하나로 묶으면 캠페인 하나의 실패가
 * 앞서 끝난 판정까지 되돌린다. 같은 판정을 다시 보내도 캠페인 쪽 결과가 같아서(#198) 재실행이 안전하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingJudgementService {

    private final CampaignQueryService campaignQuery;
    private final CampaignFundingUpdater fundingUpdater;
    private final BudgetDailyPlanRepository planRepository;
    private final PointLedgerRepository ledgerRepository;

    // 판정을 통보하고 NO_POINTS 로 판정한 캠페인 수를 돌려준다
    public int judge(LocalDate businessDate) {
        List<CampaignInfo> targets = campaignQuery.findServableOn(businessDate).stream()
                .filter(FundingJudgementService::isJudged)
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }
        List<Long> campaignIds = targets.stream().map(CampaignInfo::campaignId).toList();
        Map<Long, BudgetDailyPlan> plans = new HashMap<>();
        for (BudgetDailyPlan plan : planRepository.findByBusinessDateAndCampaignIdIn(businessDate, campaignIds)) {
            plans.put(plan.getCampaignId(), plan);
        }
        Map<Long, Long> spends = new HashMap<>();
        for (CampaignSpend spend : ledgerRepository.sumDeductForCampaignsOn(campaignIds, businessDate)) {
            spends.put(spend.campaignId(), spend.spend());
        }

        int noPoints = 0;
        for (CampaignInfo campaign : targets) {
            BudgetDailyPlan plan = plans.get(campaign.campaignId());
            if (plan == null) {
                // 예약 단계 뒤에 서빙 대상이 된 캠페인이다. 계획이 없어 판정할 값이 없고, 차감도 계획 없이는 과금하지 않는다
                log.warn("event=FUNDING_JUDGE_NO_PLAN businessDate={} campaignId={}", businessDate, campaign.campaignId());
                continue;
            }
            Judgement judgement = judgementOf(campaign, plan);
            if (judgement == Judgement.NO_POINTS) {
                noPoints++;
            }
            fundingUpdater.applyFundingJudgement(new FundingJudgement(
                    campaign.campaignId(), plan.getOwnerId(), businessDate, judgement, Cause.DAILY,
                    plan.getDailyBudget(), plan.getUnitPrice(), campaign.dailyBudget(),
                    spends.getOrDefault(campaign.campaignId(), 0L)));
        }
        log.info("event=FUNDING_JUDGE_DONE businessDate={} judged={} noPoints={}", businessDate, targets.size(), noPoints);
        return noPoints;
    }

    private static boolean isJudged(CampaignInfo campaign) {
        return campaign.status() == CampaignStatus.ACTIVE
                || (campaign.status() == CampaignStatus.PAUSED && campaign.pauseReason() == PauseReason.NO_POINTS);
    }

    private static Judgement judgementOf(CampaignInfo campaign, BudgetDailyPlan plan) {
        if (plan.getDailyBudget() < plan.getUnitPrice()) {
            return Judgement.NO_POINTS;
        }
        return campaign.status() == CampaignStatus.ACTIVE ? Judgement.OK : Judgement.RESUME;
    }
}
