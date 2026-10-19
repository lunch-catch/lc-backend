package com.launchcatch.analytics.query;

import java.util.List;

/*
 * A-10 의 조회 결과다.
 * 경고가 걸린 캠페인 가운데 요청한 쪽의 항목이다
 */
public record CampaignWarningResult(List<CampaignWarning> items, int page, int size, long totalElements) {
}
