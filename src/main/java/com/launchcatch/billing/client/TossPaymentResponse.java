package com.launchcatch.billing.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/*
 * 토스 승인과 조회 API가 함께 돌려주는 Payment 객체 중 쓰는 필드만 옮겼다.
 * 카드, 가상계좌, 영수증 같은 수단별 상세는 필요가 없다.
 *
 * ignoreUnknown을 명시한다. 토스가 필드를 더 보내도 깨지지 않는다는 뜻이 이 DTO에서 바로 읽혀야 한다.
 * status는 문자열로 받아 게이트웨이가 우리 상태로 옮긴다. 토스가 새 상태를 추가해도 역직렬화가 깨지지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TossPaymentResponse(
        String paymentKey,
        String orderId,
        String status,
        Long totalAmount,
        String approvedAt,
        Failure failure
) {

    // 승인에 실패한 결제를 조회할 때만 채워진다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Failure(String code, String message) {
    }
}
