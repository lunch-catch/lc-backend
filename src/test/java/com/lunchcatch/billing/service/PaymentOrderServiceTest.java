package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.PaymentOrderResponse;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PaymentStatus;
import com.lunchcatch.billing.entity.PointPolicy;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentOrderServiceTest {

    private static final Long OWNER = 7L;

    // UTC 로는 9월 30일이지만 KST 로는 10월 1일 00:30 이다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ClockConfig.ZONE);

    private static final PointPolicy POLICY = PointPolicy.effectiveFrom(
            LocalDate.of(2026, 1, 1), 10000, List.of(10000L, 30000L, 50000L, 100000L), null, null);

    @Mock PaymentRepository paymentRepository;
    @Mock OwnerPointPolicyService pointPolicyService;

    private PaymentOrderService service() {
        return new PaymentOrderService(paymentRepository, pointPolicyService, CLOCK);
    }

    private void noUnknownPayment() {
        when(paymentRepository.existsByOwnerIdAndStatus(OWNER, PaymentStatus.UNKNOWN)).thenReturn(false);
    }

    private void savedWithId(long id) {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", id);
            return payment;
        });
    }

    @Test
    void 정책에_맞는_금액이면_승인_전_주문을_저장하고_주문_번호를_돌려준다() {
        noUnknownPayment();
        when(pointPolicyService.currentPolicy()).thenReturn(POLICY);
        savedWithId(101L);

        PaymentOrderResponse response = service().order(OWNER, 30000);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(OWNER);
        assertThat(saved.getValue().getAmount()).isEqualTo(30000);
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(saved.getValue().getPgTid()).isNull();
        assertThat(response.paymentId()).isEqualTo(101L);
        assertThat(response.orderId()).isEqualTo(saved.getValue().getOrderId());
        assertThat(response.amount()).isEqualTo(30000);
    }

    @Test
    void 주문_번호는_KST_날짜와_무작위_값으로_만들고_토스_규칙_길이에_들어간다() {
        noUnknownPayment();
        when(pointPolicyService.currentPolicy()).thenReturn(POLICY);
        savedWithId(101L);

        String first = service().order(OWNER, 10000).orderId();
        String second = service().order(OWNER, 10000).orderId();

        assertThat(first).matches("ord_20261001_[0-9a-f]{32}").hasSizeBetween(6, 64);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 확인_중인_결제가_있으면_BILLING_008이고_주문을_만들지_않는다() {
        when(paymentRepository.existsByOwnerIdAndStatus(OWNER, PaymentStatus.UNKNOWN)).thenReturn(true);

        assertThatThrownBy(() -> service().order(OWNER, 30000))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.UNKNOWN_PAYMENT_EXISTS));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 최소_충전_금액보다_적으면_BILLING_001이고_주문을_만들지_않는다() {
        noUnknownPayment();
        when(pointPolicyService.currentPolicy()).thenReturn(POLICY);

        assertThatThrownBy(() -> service().order(OWNER, 9999))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.MIN_CHARGE_AMOUNT_NOT_MET));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void 최소_금액_이상이어도_충전_상품에_없으면_BILLING_002이고_주문을_만들지_않는다() {
        noUnknownPayment();
        when(pointPolicyService.currentPolicy()).thenReturn(POLICY);

        assertThatThrownBy(() -> service().order(OWNER, 20000))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_PRODUCT_NOT_ALLOWED));
        verify(paymentRepository, never()).save(any());
    }
}
