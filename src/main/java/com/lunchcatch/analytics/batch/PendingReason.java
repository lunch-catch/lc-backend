package com.lunchcatch.analytics.batch;

/*
 * 미처리 날짜로 남는 이유다. aggregation_pending_date.reason 의 CHECK 와 같은 값이다.
 * ADJUSTED 는 정산이 알려 준 정정 대상 영업일이고, 그 날짜를 남기는 단계는 매출 단위가 더한다.
 */
public enum PendingReason {
    FAILED,
    SOURCE_NOT_READY,
    ADJUSTED
}
