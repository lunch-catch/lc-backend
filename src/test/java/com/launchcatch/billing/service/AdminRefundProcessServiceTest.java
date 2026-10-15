package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.RefundProcessResponse;
import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.RefundRequestRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminRefundProcessServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:20:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 20);
    private static final Long ADMIN = 2L;
    private static final Long OWNER = 7L;
    private static final Long REQUEST_ID = 31L;
    private static final String REJECT_REASON = "캠페인 정산 확인 후 다시 요청해 주세요";

    private final RefundRequestRepository refundRequestRepository = mock(RefundRequestRepository.class);
    private final RefundApprovalRecorder approvalRecorder = mock(RefundApprovalRecorder.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final AdminRefundProcessService service =
            new AdminRefundProcessService(refundRequestRepository, approvalRecorder, auditLogWriter, CLOCK);

    private static RefundRequest pending() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 50_000L, null);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        return request;
    }

    private static void assertRejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void 승인은_요청의_점주_ID_를_읽어_기록기에_넘긴다() {
        RefundProcessResponse expected = new RefundProcessResponse(REQUEST_ID, RefundStatus.COMPLETED, 50_000L,
                0L, OffsetDateTime.now(CLOCK));
        when(refundRequestRepository.findOwnerIdById(REQUEST_ID)).thenReturn(Optional.of(OWNER));
        when(approvalRecorder.approve(ADMIN, REQUEST_ID, OWNER)).thenReturn(expected);

        assertThat(service.approve(ADMIN, REQUEST_ID)).isSameAs(expected);
    }

    @Test
    void 없는_요청을_승인하면_BILLING_014() {
        when(refundRequestRepository.findOwnerIdById(REQUEST_ID)).thenReturn(Optional.empty());

        assertRejected(() -> service.approve(ADMIN, REQUEST_ID), BillingErrorCode.REFUND_REQUEST_NOT_FOUND);
        verifyNoInteractions(approvalRecorder);
    }

    @Test
    void 반려하면_사유를_남기고_감사_로그를_쓴다() {
        RefundRequest request = pending();
        when(refundRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

        RefundProcessResponse response = service.reject(ADMIN, REQUEST_ID, REJECT_REASON);

        assertThat(request.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(request.getRejectReason()).isEqualTo(REJECT_REASON);
        assertThat(request.getProcessedBy()).isEqualTo(ADMIN);
        assertThat(request.getProcessedAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(RefundStatus.REJECTED);
        assertThat(response.processedAt().toString()).isEqualTo("2026-10-14T11:20+09:00");
        verify(auditLogWriter).write(ADMIN, AdminRefundProcessService.REJECT_AUDIT_ACTION, "31",
                "ownerId=7;amount=50000");
    }

    @Test
    void 없는_요청을_반려하면_BILLING_014() {
        when(refundRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.empty());

        assertRejected(() -> service.reject(ADMIN, REQUEST_ID, REJECT_REASON),
                BillingErrorCode.REFUND_REQUEST_NOT_FOUND);
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void 이미_처리된_요청을_반려하면_BILLING_015() {
        RefundRequest request = pending();
        request.complete(3L, NOW.minusHours(1));
        when(refundRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));

        assertRejected(() -> service.reject(ADMIN, REQUEST_ID, REJECT_REASON),
                BillingErrorCode.REFUND_REQUEST_ALREADY_PROCESSED);
        assertThat(request.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        verifyNoInteractions(auditLogWriter);
    }
}
