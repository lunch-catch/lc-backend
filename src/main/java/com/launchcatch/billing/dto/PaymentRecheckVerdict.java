package com.launchcatch.billing.dto;

import java.time.LocalDateTime;

/**
 * 결제대행사 조회 결과를 보고 내린 결제 처리 판정이다.
 *
 * @param decision   어떻게 처리할지
 * @param approvedAt APPROVE 일 때 결제대행사가 알려 준 승인 시각. 없으면 null
 * @param reason     FAIL 이면 실패 사유, MISS 이면 확정하지 못한 사유. 나머지는 null
 */
public record PaymentRecheckVerdict(Decision decision, LocalDateTime approvedAt, String reason) {

    public enum Decision {
        /** 승인이 확인됐다. 충전한다. */
        APPROVE,
        /** 승인 실패가 확인됐다. */
        FAIL,
        /** 승인 전 만료나 취소가 확인됐다. 충전 없이 닫는다. */
        CANCEL,
        /** 확정하지 못했다. 결제는 확인 중으로 둔다. */
        MISS
    }

    public static PaymentRecheckVerdict approve(LocalDateTime approvedAt) {
        return new PaymentRecheckVerdict(Decision.APPROVE, approvedAt, null);
    }

    public static PaymentRecheckVerdict fail(String reason) {
        return new PaymentRecheckVerdict(Decision.FAIL, null, reason);
    }

    public static PaymentRecheckVerdict cancel() {
        return new PaymentRecheckVerdict(Decision.CANCEL, null, null);
    }

    public static PaymentRecheckVerdict miss(String reason) {
        return new PaymentRecheckVerdict(Decision.MISS, null, reason);
    }
}
