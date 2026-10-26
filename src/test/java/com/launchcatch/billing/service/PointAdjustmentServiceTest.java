package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PointAdjustmentRequest;
import com.launchcatch.billing.dto.PointAdjustmentResponse;
import com.launchcatch.billing.dto.PointAdjustmentResult;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PointAdjustmentServiceTest {

    private static final Long ADMIN = 2L;
    private static final Long OWNER = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:20:00Z"), ClockConfig.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final OwnerStatusQuery ownerStatusQuery = mock(OwnerStatusQuery.class);
    private final PointAdjustmentRecorder recorder = mock(PointAdjustmentRecorder.class);
    private final PointAdjustmentService service = new PointAdjustmentService(ownerStatusQuery, recorder, CLOCK);

    private static PointAdjustmentRequest request(long amount, Long mismatchId) {
        return new PointAdjustmentRequest("req-1", OWNER, amount, "중복 과금 정정", null, null, mismatchId);
    }

    private static PointAdjustmentRequest campaignRequest(Long campaignId, LocalDate target) {
        return new PointAdjustmentRequest("req-1", OWNER, 1000L, "중복 과금 정정", campaignId, target, null);
    }

    private static void assertRejected(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void 금액이_0이면_점주를_보지_않고_거절한다() {
        assertRejected(() -> service.adjust(ADMIN, request(0L, null)), CommonErrorCode.INVALID_INPUT);

        verify(ownerStatusQuery, never()).findStatus(any());
        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 대사_건을_닫는_음수_조정도_허용_여부는_대사_건을_읽는_기록기에_맡긴다() {
        PointAdjustmentRequest request = request(-1000L, 55L);
        when(ownerStatusQuery.findStatus(OWNER)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

        service.adjust(ADMIN, request);

        verify(recorder).record(ADMIN, request);
    }

    @Test
    void 캠페인이_있는데_정정_대상_영업일이_없으면_거절한다() {
        assertRejected(() -> service.adjust(ADMIN, campaignRequest(12L, null)), CommonErrorCode.INVALID_INPUT);

        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 캠페인이_없는데_정정_대상_영업일이_있으면_거절한다() {
        assertRejected(() -> service.adjust(ADMIN, campaignRequest(null, TODAY.minusDays(1))),
                CommonErrorCode.INVALID_INPUT);

        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 정정_대상_영업일이_미래면_거절한다() {
        assertRejected(() -> service.adjust(ADMIN, campaignRequest(12L, TODAY.plusDays(1))),
                CommonErrorCode.INVALID_INPUT);

        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 정정_대상_영업일이_오늘이면_기록한다() {
        PointAdjustmentRequest request = campaignRequest(12L, TODAY);
        when(ownerStatusQuery.findStatus(OWNER)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

        service.adjust(ADMIN, request);

        verify(recorder).record(ADMIN, request);
    }

    @Test
    void 없는_점주면_기록하지_않고_거절한다() {
        when(ownerStatusQuery.findStatus(OWNER)).thenReturn(Optional.empty());

        assertRejected(() -> service.adjust(ADMIN, request(1000L, null)), BillingErrorCode.OWNER_NOT_FOUND);

        verify(recorder, never()).record(any(), any());
    }

    @Test
    void 탈퇴한_점주도_있는_점주라_조정을_기록한다() {
        PointAdjustmentRequest request = request(-500L, null);
        PointAdjustmentResult result = new PointAdjustmentResult(
                new PointAdjustmentResponse(1L, OWNER, -500L, null, null, "중복 과금 정정", OffsetDateTime.now()), true);
        when(ownerStatusQuery.findStatus(OWNER)).thenReturn(Optional.of(OwnerAccountStatus.WITHDRAWN));
        when(recorder.record(ADMIN, request)).thenReturn(result);

        assertThat(service.adjust(ADMIN, request)).isSameAs(result);
    }
}
