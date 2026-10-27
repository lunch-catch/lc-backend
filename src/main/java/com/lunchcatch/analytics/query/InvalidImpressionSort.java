package com.lunchcatch.analytics.query;

import java.util.Arrays;

/*
 * A-09 의 sortBy 다.
 * 요청 값과 SQL 의 정렬 열을 짝짓고, 이 목록에 없는 값은 쿼리에 들어가지 못한다
 */
public enum InvalidImpressionSort {
    COUNT("count", "total_count"),
    MEMBER_ID("memberId", "member_id"),
    CAMPAIGN_ID("campaignId", "campaign_id");

    private final String parameter;
    private final String column;

    InvalidImpressionSort(String parameter, String column) {
        this.parameter = parameter;
        this.column = column;
    }

    public String column() {
        return column;
    }

    public static InvalidImpressionSort from(String parameter) {
        return Arrays.stream(values())
                .filter(sort -> sort.parameter.equals(parameter))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 정렬 기준입니다: " + parameter));
    }
}
