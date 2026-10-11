package com.launchcatch.billing.client;

import java.time.LocalDateTime;

/**
 * 승인 성공 결과.
 *
 * @param paymentKey PG가 돌려준 결제 키
 * @param orderId    승인된 주문 번호
 * @param amount     승인된 금액
 * @param approvedAt 승인 시각. 앱 시간대(Asia/Seoul)로 바꾼 값
 */
public record PaymentGatewayApproval(String paymentKey, String orderId, long amount, LocalDateTime approvedAt) {
}
