package com.launchcatch.analytics;

// 일별 집계의 실행과 조회가 같은 작업 식별자를 사용한다.
public final class DailyAggregation {

    /** batch_execution_log.job_name 이다. 영업일은 붙이지 않는다. */
    public static final String JOB_NAME = "DAILY_AGGREGATION";

    private DailyAggregation() {
    }
}
