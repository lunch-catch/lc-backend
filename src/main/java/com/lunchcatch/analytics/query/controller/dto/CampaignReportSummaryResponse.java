package com.lunchcatch.analytics.query.controller.dto;

import com.lunchcatch.analytics.query.CampaignReportTotal;
import java.util.List;

/*
 * 관리자 캠페인 리포트(A-14) 응답이다.
 * todayStatus 는 당일이 집계 전이라 늘 IN_PROGRESS 다. 값은 전일까지 확정된 집계의 합이다.
 */
public record CampaignReportSummaryResponse(List<Item> items) {

    private static final String TODAY_STATUS = "IN_PROGRESS";

    public static CampaignReportSummaryResponse from(List<CampaignReportTotal> totals) {
        return new CampaignReportSummaryResponse(totals.stream()
                .map(total -> new Item(total.campaignId(), total.spentAmount(), total.validImpressionCount(),
                        TODAY_STATUS))
                .toList());
    }

    public record Item(long campaignId, long spentAmount, long validImpressionCount, String todayStatus) {
    }
}
