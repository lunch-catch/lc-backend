package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.SettlementMismatchItem;
import com.lunchcatch.billing.entity.MismatchCheckType;
import com.lunchcatch.billing.entity.MismatchResolutionType;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRow;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.exception.ErrorCode;
import com.lunchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SettlementMismatchResolutionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:20:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 20);
    private static final Long ADMIN = 2L;
    private static final Long MISMATCH = 55L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);

    private final SettlementMismatchRepository mismatchRepository = mock(SettlementMismatchRepository.class);
    private final SettlementMismatchQueryService queryService = mock(SettlementMismatchQueryService.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final SettlementMismatchResolutionService service =
            new SettlementMismatchResolutionService(mismatchRepository, queryService, auditLogWriter, CLOCK);

    private static SettlementMismatchRow row(MismatchResolutionType resolution) {
        return new SettlementMismatchRow(MISMATCH, MismatchCheckType.CAMPAIGN_SPEND_VS_LEDGER, 12L, BUSINESS_DATE,
                1000L, 1100L, resolution, 301L, NOW.minusDays(1), null, null);
    }

    private static SettlementMismatchItem closedItem(long expected, long actual) {
        return new SettlementMismatchItem(MISMATCH, MismatchCheckType.CAMPAIGN_SPEND_VS_LEDGER, 12L, 7L, BUSINESS_DATE,
                expected, actual, actual - expected, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED, null, 301L,
                null, ADMIN);
    }

    private void assertRejected(ErrorCode expected) {
        assertThatThrownBy(() -> service.closeWithoutAdjustment(ADMIN, MISMATCH))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void 열린_건을_조정_없이_닫고_처리자와_감사_로그를_남긴다() {
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED, ADMIN, NOW))
                .thenReturn(1);
        SettlementMismatchItem closed = closedItem(1000L, 1100L);
        when(queryService.get(MISMATCH)).thenReturn(closed);

        assertThat(service.closeWithoutAdjustment(ADMIN, MISMATCH)).isSameAs(closed);

        verify(auditLogWriter).write(ADMIN, SettlementMismatchResolutionService.AUDIT_ACTION, "55",
                "resolutionType=NO_ADJUSTMENT_REQUIRED");
    }

    @Test
    void 카운터_대_원장_대사_건은_카운터가_크든_원장이_크든_조정_없이_닫힌다() {
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED, ADMIN, NOW))
                .thenReturn(1);
        // 원장 1000, 카운터 1100 이면 카운터가 크고 원장 1100, 카운터 1000 이면 원장이 크다
        when(queryService.get(MISMATCH)).thenReturn(closedItem(1000L, 1100L), closedItem(1100L, 1000L));

        assertThat(service.closeWithoutAdjustment(ADMIN, MISMATCH).resolutionType())
                .isEqualTo(MismatchResolutionType.NO_ADJUSTMENT_REQUIRED);
        assertThat(service.closeWithoutAdjustment(ADMIN, MISMATCH).resolutionType())
                .isEqualTo(MismatchResolutionType.NO_ADJUSTMENT_REQUIRED);
    }

    @Test
    void 없는_건이면_거절한다() {
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED, ADMIN, NOW))
                .thenReturn(0);
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.empty());

        assertRejected(BillingErrorCode.SETTLEMENT_MISMATCH_NOT_FOUND);

        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void 이미_해결된_건이면_거절한다() {
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED, ADMIN, NOW))
                .thenReturn(0);
        when(mismatchRepository.findRowById(MISMATCH))
                .thenReturn(Optional.of(row(MismatchResolutionType.MANUAL_ADJUSTMENT)));

        assertRejected(BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED);

        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }
}
