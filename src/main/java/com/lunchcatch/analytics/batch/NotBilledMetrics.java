package com.lunchcatch.analytics.batch;

import java.util.List;

/*
 * 하루 미과금 노출 지표다. 캠페인 하나의 미과금 노출을 사유마다 한 행으로 담는다. 건수가 0인 사유는 행이 없다.
 * 예산, 중단, 예산 계획 없음은 정산이, 쿠폰 소진 뒤 노출은 광고 서빙이 센다.
 */
public record NotBilledMetrics(List<Row> rows) {

    public record Row(long campaignId, NotBilledReason reason, long count) {
    }
}
