package com.lunchcatch.analytics.batch;

import java.util.List;

/*
 * 하루 무효 노출 지표다. 회원 하나가 캠페인 하나로 보낸 무효 노출을 사유마다 한 행으로 담는다. 건수가 0인 사유는
 * 행이 없다.
 */
public record InvalidMetrics(List<Row> rows) {

    public record Row(long campaignId, long memberId, InvalidReasonCode reasonCode, long count) {
    }
}
