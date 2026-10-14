package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignFundingUpdater;
import com.launchcatch.campaign.contract.FundingJudgement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 정산의 FundingJudgement를 캠페인 상태 전이로 옮긴다. 금액과 판정 계기는 캠페인이 보관하지
 * 않고 CampaignStatusLog.reason에 감사용으로만 남긴다.
 */
@Service
@RequiredArgsConstructor
public class CampaignFundingUpdaterImpl implements CampaignFundingUpdater {

    private final CampaignStatusService campaignStatusService;

    @Override
    public void applyFundingJudgement(FundingJudgement judgement) {
        String note = judgementNote(judgement);
        switch (judgement.judgement()) {
            case NO_POINTS -> campaignStatusService.pauseFromFunding(
                    judgement.campaignId(), judgement.businessDate(), note);
            case RESUME -> campaignStatusService.resumeFromFunding(
                    judgement.campaignId(), judgement.businessDate(), note);
            case OK -> { }
        }
    }

    private String judgementNote(FundingJudgement judgement) {
        return "cause=%s reservedAmount=%d unitPrice=%d configuredDailyBudget=%d todaySpend=%d".formatted(
                judgement.cause(), judgement.reservedAmount(), judgement.unitPrice(),
                judgement.configuredDailyBudget(), judgement.todaySpend());
    }
}
