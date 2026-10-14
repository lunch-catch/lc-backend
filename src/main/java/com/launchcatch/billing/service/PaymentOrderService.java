package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PaymentOrderResponse;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.entity.PointPolicy;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 결제창을 열기 전에 충전 주문을 만든다.
 *
 * 금액을 이때 검증해 저장해 두고, 승인 확정은 이 저장값으로만 결제대행사에 요청한다. 클라이언트가 승인 때
 * 다시 보내는 금액은 쓰지 않는다. 위변조 방지의 핵심이 이 순서다.
 *
 * 확인 중(UNKNOWN)인 결제가 있으면 새 주문을 막는다. 승인 여부를 모르는 결제와 새 결제가 겹치면
 * 점주가 두 번 결제했는지 알 수 없다. 승인 시도 전(REQUESTED)인 주문은 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentOrderService {

    private static final String ORDER_ID_PREFIX = "ord_";

    private final PaymentRepository paymentRepository;
    private final OwnerPointPolicyService pointPolicyService;
    private final Clock clock;

    @Transactional
    public PaymentOrderResponse order(Long ownerId, long amount) {
        if (paymentRepository.existsByOwnerIdAndStatus(ownerId, PaymentStatus.UNKNOWN)) {
            throw new BillingException(BillingErrorCode.UNKNOWN_PAYMENT_EXISTS);
        }
        PointPolicy policy = pointPolicyService.currentPolicy();
        if (amount < policy.getMinChargeAmount()) {
            throw new BillingException(BillingErrorCode.MIN_CHARGE_AMOUNT_NOT_MET);
        }
        if (!policy.offers(amount)) {
            throw new BillingException(BillingErrorCode.CHARGE_PRODUCT_NOT_ALLOWED);
        }
        Payment payment = paymentRepository.save(Payment.order(ownerId, newOrderId(), amount));
        return PaymentOrderResponse.from(payment);
    }

    /*
     * 주문 번호는 ord_ 와 KST 날짜, 하이픈을 뺀 UUID 로 만든다. 45자라 컬럼(64자)과 토스 규칙(6~64자, 영문 숫자 _ -)에 들어간다.
     * 충돌은 다시 시도하지 않는다. UUID 의 무작위성이면 uk_payment_order_id 에 걸릴 일이 사실상 없다.
     */
    private String newOrderId() {
        String date = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        return ORDER_ID_PREFIX + date + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
