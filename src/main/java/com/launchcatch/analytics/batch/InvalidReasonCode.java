package com.launchcatch.analytics.batch;

/*
 * 무효 노출의 사유다.
 * daily_invalid_analytics.reason_code 에 같은 이름으로 남는다
 */
public enum InvalidReasonCode {
    /*
     * serve 기록이 없다.
     * 노출 인정 시간이 지났거나 발급하지 않은 serve_id 다
     */
    EXPIRED_OR_UNKNOWN,
    // 다른 사용자가 받은 카드의 serve_id 다
    NOT_OWNER
}
