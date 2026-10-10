package com.launchcatch.billing.client;

import java.time.LocalDateTime;

/**
 * PG 결제 조회 결과.
 *
 * @param status         PG 상태를 우리 쪽 네 가지로 줄인 값
 * @param paymentKey     PG 결제 키. NOT_FOUND이면 null
 * @param orderId        주문 번호. NOT_FOUND이면 null
 * @param amount         결제 금액. NOT_FOUND이면 0
 * @param approvedAt     승인 시각. 승인된 적이 없으면 null
 * @param failureCode    실패 코드. FAILED일 때만 값이 있을 수 있다
 * @param failureMessage 실패 사유. FAILED일 때만 값이 있을 수 있다
 */
public record PaymentGatewayInquiryResult(
        Status status,
        String paymentKey,
        String orderId,
        long amount,
        LocalDateTime approvedAt,
        String failureCode,
        String failureMessage
) {

    public enum Status {
        /** 승인이 끝났다. */
        APPROVED,
        /** 승인된 뒤 PG에서 취소됐다. 전액이든 일부든 같다. 대사 불일치로 기록할 대상이다. */
        CANCELED,
        /** 승인에 실패했거나 승인 유효시간이 지났다. */
        FAILED,
        /** 아직 결론이 나지 않았다. 처음 보는 PG 상태도 여기로 모은다. */
        PENDING,
        /** PG에 이 결제가 없다. 승인 요청이 PG에 닿지 않았다는 뜻일 수 있다. */
        NOT_FOUND
    }

    public static PaymentGatewayInquiryResult notFound() {
        return new PaymentGatewayInquiryResult(Status.NOT_FOUND, null, null, 0L, null, null, null);
    }
}
