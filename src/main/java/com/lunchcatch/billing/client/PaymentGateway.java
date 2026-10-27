package com.lunchcatch.billing.client;

/**
 * 결제대행사(PG) 호출 계약. 승인과 조회만 있다.
 *
 * 취소는 일부러 없다. 승인 뒤의 PG 취소는 자동으로 되돌리지 않고 대사 불일치로 기록하는 것이
 * 명세다. 하루 거래 목록 조회는 대사 작업이 PG 문서를 확인한 뒤 여기에 더한다.
 *
 * 구현체는 모두 이 계약을 따른다. 호출하는 쪽은 예외 종류로 다음을 판단한다.
 *
 * <ul>
 *   <li>{@link PgRejectedException}: PG가 확실히 거절했다. 재시도해도 같은 결과이므로 실패로 확정해도 된다.</li>
 *   <li>{@link PgNotSentException}: PG가 요청을 처리하지 않았음이 확실하다. 요청이 나가지 않았거나
 *       인증 단계에서 거부됐다. 안전하게 다시 보낼 수 있다.</li>
 *   <li>{@link PgUnknownException}: 요청이 나갔을 수 있고 결과를 모른다. 실패로 단정하지 말고
 *       조회로 확인해야 한다.</li>
 * </ul>
 *
 * 이 계약은 트랜잭션 밖에서 부르는 것을 전제로 한다. 응답을 기다리는 동안 DB 연결을 쥐지 않기 위해서다.
 */
public interface PaymentGateway {

    /**
     * 결제 승인을 요청한다.
     *
     * @param paymentKey 프론트가 결제창에서 받은 PG 결제 키
     * @param orderId    결제창을 열기 전에 우리가 발급한 주문 번호
     * @param amount     승인 금액. 호출하는 쪽이 저장해 둔 금액을 넘긴다
     * @throws PgRejectedException 확실한 거절
     * @throws PgNotSentException  처리되지 않았음이 확실한 실패 (연결, DNS, 풀 대기 초과, 서킷 오픈, 인증 거부)
     * @throws PgUnknownException  응답 불명 (읽기 타임아웃, 5xx, 해석할 수 없는 응답)
     */
    PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount);

    /**
     * 주문 번호로 결제를 조회한다.
     * PG에 해당 결제가 없으면 예외가 아니라 {@code NOT_FOUND} 결과를 돌려준다.
     *
     * @throws PgNotSentException 요청이 처리되지 않았다
     * @throws PgUnknownException 그 밖의 실패. 조회는 부작용이 없으므로 나중에 다시 부르면 된다
     */
    PaymentGatewayInquiryResult inquireByOrderId(String orderId);

    /**
     * 결제 키로 결제를 조회한다. 승인 결과 복구와 웹훅 검증, 대사에 쓴다.
     * 예외 규칙은 {@link #inquireByOrderId(String)}와 같다.
     */
    PaymentGatewayInquiryResult inquireByPaymentKey(String paymentKey);
}
