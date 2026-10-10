package com.launchcatch.billing.client;

// 토스 승인 API 요청 바디. 문서가 요구하는 세 필드 그대로다.
record TossConfirmRequest(String paymentKey, String orderId, long amount) {
}
