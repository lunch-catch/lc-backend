package com.launchcatch.global.util;

import com.launchcatch.campaign.contract.CampaignBudgetReader;

// 예산을 화면용 문자열로 바꾼다
public final class CampaignBudgetFormatter {

    private CampaignBudgetFormatter() {
    }

    public static String format(CampaignBudgetReader reader, Long campaignId) {
        return reader.read(campaignId).getDailyBudget() + "원";
    }
}
