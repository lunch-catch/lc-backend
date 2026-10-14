package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.RefundProcessResponse;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.RefundRequestRepository;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

class RefundApprovalRecorderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:20:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 20);
    private static final Long ADMIN = 2L;
    private static final Long OWNER = 7L;
    private static final Long REQUEST_ID = 31L;
    private static final long AMOUNT = 50_000L;

    private final PointAccountRepository accountRepository = mock(PointAccountRepository.class);
    private final PointLedgerRepository ledgerRepository = mock(PointLedgerRepository.class);
    private final RefundRequestRepository refundRequestRepository = mock(RefundRequestRepository.class);
    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final RefundApprovalRecorder recorder = new RefundApprovalRecorder(accountRepository,
            ledgerRepository, refundRequestRepository, campaignQueryService, auditLogWriter, CLOCK);

    private RefundRequest request;

    @BeforeEach
    void 처리를_기다리는_요청이_있고_잔액이_요청_금액과_같다() {
        request = RefundRequest.requestAll(OWNER, AMOUNT, null);
        ReflectionTestUtils.setField(request, "id", REQUEST_ID);
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.of(1L));
        when(refundRequestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(false);
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(AMOUNT);
    }

    private void assertRejected(ErrorCode expected) {
        assertThatThrownBy(() -> recorder.approve(ADMIN, REQUEST_ID, OWNER))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
        verify(ledgerRepository, never()).save(any());
        verifyNoInteractions(auditLogWriter);
    }

    @Test
    void 잔액만큼_환불을_원장에_기록하고_요청을_완료로_닫는다() {
        RefundProcessResponse response = recorder.approve(ADMIN, REQUEST_ID, OWNER);

        ArgumentCaptor<PointLedger> saved = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgerRepository).save(saved.capture());
        assertThat(saved.getValue().getEntryType()).isEqualTo(EntryType.REFUND);
        assertThat(saved.getValue().getAmount()).isEqualTo(-AMOUNT);
        assertThat(saved.getValue().getRefundRequestId()).isEqualTo(REQUEST_ID);
        assertThat(request.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(request.getProcessedBy()).isEqualTo(ADMIN);
        assertThat(request.getProcessedAt()).isEqualTo(NOW);
        assertThat(response.refundRequestId()).isEqualTo(REQUEST_ID);
        assertThat(response.status()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(response.amount()).isEqualTo(AMOUNT);
        assertThat(response.fee()).isZero();
        assertThat(response.processedAt().toString()).isEqualTo("2026-10-14T11:20+09:00");
        verify(auditLogWriter).write(ADMIN, RefundApprovalRecorder.AUDIT_ACTION, "31",
                "ownerId=7;amount=50000");
    }

    @Test
    void 잠금_대기_상한을_넘기면_BILLING_006() {
        when(accountRepository.lockByOwnerId(OWNER)).thenThrow(new CannotAcquireLockException("timeout"));

        assertRejected(BillingErrorCode.LEDGER_UNAVAILABLE);
        verifyNoInteractions(refundRequestRepository);
    }

    @Test
    void 이미_처리된_요청이면_BILLING_015() {
        request.reject(3L, "중복 요청", NOW.minusHours(1));

        assertRejected(BillingErrorCode.REFUND_REQUEST_ALREADY_PROCESSED);
    }

    @Test
    void 요청_뒤에_집행_중_캠페인이_생겼으면_BILLING_010() {
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(true);

        assertRejected(BillingErrorCode.LIVE_CAMPAIGN_EXISTS);
        assertThat(request.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 잔액이_요청_금액보다_적으면_BILLING_013() {
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(AMOUNT - 1);

        assertRejected(BillingErrorCode.INSUFFICIENT_BALANCE);
        assertThat(request.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }
}
