package com.launchcatch.billing.dto;

/*
 * 결제대행사가 보내는 결제 상태 변경 웹훅 본문이다.
 * 우리는 data 의 결제 키와 주문 번호만 꺼내고 나머지 필드(상태 포함)는 읽지 않는다.
 * 이 웹훅은 서명으로 검증할 수 없어서 본문의 상태값을 믿지 않고 결제대행사에 다시 물어 확정한다.
 */
public record PaymentWebhookRequest(String eventType, Data data) {

    public static final String STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    public record Data(String paymentKey, String orderId) {
    }
}
