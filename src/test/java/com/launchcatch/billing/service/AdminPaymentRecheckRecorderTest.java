package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PaymentRecheckVerdict;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminPaymentRecheckRecorderTest {

    private static final Long ADMIN = 3L;
    private static final Long PAYMENT_ID = 101L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 11, 10, 0, 3);

    @Mock PaymentResultService resultService;
    @Mock AuditLogWriter auditLogWriter;

    private AdminPaymentRecheckRecorder recorder() {
        return new AdminPaymentRecheckRecorder(resultService, auditLogWriter);
    }

    private Payment unknown() {
        Payment payment = Payment.order(7L, "ord_1", 30000L);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        payment.assignPgTid("tgen_1");
        payment.markUnknown();
        return payment;
    }

    private Payment approved() {
        Payment payment = unknown();
        payment.approve(APPROVED_AT);
        return payment;
    }

    @Test
    void 승인_판정은_충전을_기록하고_같은_자리에서_감사_로그를_남긴다() {
        when(resultService.approve(PAYMENT_ID, APPROVED_AT)).thenReturn(approved());

        recorder().record(ADMIN, PAYMENT_ID, PaymentRecheckVerdict.approve(APPROVED_AT));

        InOrder order = inOrder(resultService, auditLogWriter);
        order.verify(resultService).approve(PAYMENT_ID, APPROVED_AT);
        order.verify(auditLogWriter).write(ADMIN, "PAYMENT_RECHECK", "101", "decision=APPROVE;status=APPROVED");
    }

    @Test
    void 실패_판정은_사유와_함께_실패로_닫는다() {
        Payment failed = unknown();
        failed.fail("거절");
        when(resultService.fail(PAYMENT_ID, "거절")).thenReturn(failed);

        recorder().record(ADMIN, PAYMENT_ID, PaymentRecheckVerdict.fail("거절"));

        verify(auditLogWriter).write(ADMIN, "PAYMENT_RECHECK", "101", "decision=FAIL;status=FAILED");
    }

    @Test
    void 취소_판정은_충전_없이_취소로_닫는다() {
        Payment cancelled = unknown();
        cancelled.cancel(APPROVED_AT, null);
        when(resultService.cancel(PAYMENT_ID)).thenReturn(cancelled);

        recorder().record(ADMIN, PAYMENT_ID, PaymentRecheckVerdict.cancel());

        verify(auditLogWriter).write(ADMIN, "PAYMENT_RECHECK", "101", "decision=CANCEL;status=CANCELLED");
    }

    @Test
    void 확정하지_못했으면_시도_횟수를_건드리지_않는_기록을_하고_로그는_남긴다() {
        Payment stillUnknown = unknown();
        when(resultService.recordAdminRecheckMiss(PAYMENT_ID, "처리 중")).thenReturn(stillUnknown);

        Payment result = recorder().record(ADMIN, PAYMENT_ID, PaymentRecheckVerdict.miss("처리 중"));

        assertThat(result).isSameAs(stillUnknown);
        verify(auditLogWriter).write(ADMIN, "PAYMENT_RECHECK", "101", "decision=MISS;status=UNKNOWN");
    }
}
