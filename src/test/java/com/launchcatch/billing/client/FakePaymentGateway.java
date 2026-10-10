package com.launchcatch.billing.client;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * 결제 프로세스 시험에서 PaymentGateway 대신 쓰는 가짜 구현이다. 네트워크도 서킷도 없다.
 * 시험이 원하는 응답이나 예외를 미리 정해 두고, 서비스가 무엇을 불렀는지 기록으로 확인한다.
 *
 * 기본 동작은 승인 성공이고, 조회는 등록된 결제가 없으면 NOT_FOUND 를 돌려준다.
 * 실제 구현의 실패 분류는 TossPaymentGatewayTest 가 본다. 여기서는 그 결과 예외를 그대로 던질 뿐이다.
 */
public class FakePaymentGateway implements PaymentGateway {

    public record ConfirmCall(String paymentKey, String orderId, long amount) {
    }

    private static final LocalDateTime DEFAULT_APPROVED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);

    private final List<ConfirmCall> confirmCalls = new ArrayList<>();
    private final Map<String, PaymentGatewayInquiryResult> byOrderId = new HashMap<>();
    private final Map<String, PaymentGatewayInquiryResult> byPaymentKey = new HashMap<>();

    private RuntimeException confirmFailure;
    private RuntimeException inquiryFailure;

    /** 다음 승인부터 이 예외를 던진다. null 이면 다시 성공한다. */
    public FakePaymentGateway failConfirmWith(RuntimeException failure) {
        this.confirmFailure = failure;
        return this;
    }

    /** 다음 조회부터 이 예외를 던진다. null 이면 다시 정상 응답한다. */
    public FakePaymentGateway failInquiryWith(RuntimeException failure) {
        this.inquiryFailure = failure;
        return this;
    }

    /** 주문 번호와 결제 키 양쪽 조회에 이 결과를 돌려준다. */
    public FakePaymentGateway givenPayment(PaymentGatewayInquiryResult result) {
        byOrderId.put(result.orderId(), result);
        byPaymentKey.put(result.paymentKey(), result);
        return this;
    }

    public List<ConfirmCall> confirmCalls() {
        return List.copyOf(confirmCalls);
    }

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount) {
        confirmCalls.add(new ConfirmCall(paymentKey, orderId, amount));
        if (confirmFailure != null) {
            throw confirmFailure;
        }
        return new PaymentGatewayApproval(paymentKey, orderId, amount, DEFAULT_APPROVED_AT);
    }

    @Override
    public PaymentGatewayInquiryResult inquireByOrderId(String orderId) {
        return inquire(byOrderId, orderId);
    }

    @Override
    public PaymentGatewayInquiryResult inquireByPaymentKey(String paymentKey) {
        return inquire(byPaymentKey, paymentKey);
    }

    private PaymentGatewayInquiryResult inquire(Map<String, PaymentGatewayInquiryResult> registered, String key) {
        if (inquiryFailure != null) {
            throw inquiryFailure;
        }
        return registered.getOrDefault(key, PaymentGatewayInquiryResult.notFound());
    }
}
