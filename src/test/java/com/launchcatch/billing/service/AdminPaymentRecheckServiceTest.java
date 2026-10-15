package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminPaymentItem;
import com.launchcatch.billing.dto.PaymentRecheckVerdict;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminPaymentRecheckServiceTest {

    private static final Long ADMIN = 3L;
    private static final Long PAYMENT_ID = 101L;

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentRecheckService recheckService;
    @Mock AdminPaymentRecheckRecorder recorder;
    @Mock AdminPaymentQueryService queryService;

    private AdminPaymentRecheckService service() {
        return new AdminPaymentRecheckService(paymentRepository, recheckService, recorder, queryService);
    }

    private Payment attempted() {
        Payment payment = Payment.order(7L, "ord_1", 30000L);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.assignPgTid("tgen_1");
        return payment;
    }

    private void assertRejected(BillingErrorCode code) {
        assertThatThrownBy(() -> service().recheck(ADMIN, PAYMENT_ID)).isInstanceOfSatisfying(BillingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
        verifyNoInteractions(recheckService, recorder, queryService);
    }

    @Test
    void 확인_중인_결제는_조회한_뒤_기록하고_갱신된_결제를_돌려준다() {
        Payment payment = attempted();
        payment.markUnknown();
        PaymentRecheckVerdict verdict = PaymentRecheckVerdict.approve(LocalDateTime.of(2026, 10, 11, 10, 0));
        AdminPaymentItem item = mock(AdminPaymentItem.class);
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(recheckService.judge(payment)).thenReturn(verdict);
        when(queryService.get(PAYMENT_ID)).thenReturn(item);

        AdminPaymentItem result = service().recheck(ADMIN, PAYMENT_ID);

        assertThat(result).isSameAs(item);
        InOrder order = inOrder(recheckService, recorder, queryService);
        order.verify(recheckService).judge(payment);
        order.verify(recorder).record(ADMIN, PAYMENT_ID, verdict);
        order.verify(queryService).get(PAYMENT_ID);
    }

    @Test
    void 없는_결제면_BILLING_004다() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        assertRejected(BillingErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    void 확인_중이_아닌_결제는_BILLING_005이고_결제대행사를_부르지_않는다() {
        Payment payment = attempted();
        payment.approve(LocalDateTime.of(2026, 10, 11, 10, 0));
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

        assertRejected(BillingErrorCode.PAYMENT_NOT_RECHECKABLE);
    }
}
