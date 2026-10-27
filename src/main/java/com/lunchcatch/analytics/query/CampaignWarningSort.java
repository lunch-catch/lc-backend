package com.lunchcatch.analytics.query;

import java.util.Arrays;

/*
 * A-10 의 sortBy 다.
 * 이 목록에 없는 값은 받지 않는다
 */
public enum CampaignWarningSort {
    INVALID_RATIO("invalidRatio"),
    TOP_MEMBERS_SHARE("topMembersShare"),
    CAMPAIGN_ID("campaignId");

    private final String parameter;

    CampaignWarningSort(String parameter) {
        this.parameter = parameter;
    }

    public static CampaignWarningSort from(String parameter) {
        return Arrays.stream(values())
                .filter(sort -> sort.parameter.equals(parameter))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 정렬 기준입니다: " + parameter));
    }
}
