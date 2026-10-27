package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.PointAdjustmentRequest;
import com.lunchcatch.billing.dto.PointAdjustmentResult;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.entity.MismatchCheckType;
import com.lunchcatch.billing.entity.MismatchResolutionType;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PaymentRepository;
import com.lunchcatch.billing.repository.PointAccountRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRow;
import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.exception.ErrorCode;
import com.lunchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;

class PointAdjustmentRecorderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T02:20:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 20);
    private static final Long ADMIN = 2L;
    private static final Long OWNER = 7L;
    private static final Long OTHER_OWNER = 8L;
    private static final Long CAMPAIGN = 12L;
    private static final Long PAYMENT = 101L;
    private static final Long MISMATCH = 55L;
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 13);
    private static final String KEY = "req-1";
    private static final String REASON = "중복 과금 정정";

    private final PointAccountRepository accountRepository = mock(PointAccountRepository.class);
    private final PointLedgerRepository ledgerRepository = mock(PointLedgerRepository.class);
    private final SettlementMismatchRepository mismatchRepository = mock(SettlementMismatchRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final PointAdjustmentRecorder recorder = new PointAdjustmentRecorder(accountRepository,
            ledgerRepository, mismatchRepository, paymentRepository, campaignQueryService, auditLogWriter, CLOCK);

    @BeforeEach
    void 계정_행은_있고_같은_키의_기록은_없다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.of(1L));
        when(ledgerRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.empty());
        when(ledgerRepository.save(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0), 9101L));
        lenient().when(campaignQueryService.findAllById(List.of(CAMPAIGN))).thenReturn(List.of(campaign(OWNER)));
        lenient().when(ledgerRepository.findByPaymentIdAndEntryType(PAYMENT, EntryType.CHARGE))
                .thenReturn(Optional.empty());
        lenient().when(ledgerRepository.sumBalance(OWNER)).thenReturn(1_000_000L);
    }

    private static PointLedger persisted(PointLedger ledger, long id) {
        ReflectionTestUtils.setField(ledger, "id", id);
        ReflectionTestUtils.setField(ledger, "createdAt", NOW);
        return ledger;
    }

    private static PointAdjustmentRequest request(long amount, Long campaignId, Long mismatchId) {
        return new PointAdjustmentRequest(KEY, OWNER, amount, REASON, campaignId, campaignId == null ? null : TARGET, mismatchId);
    }

    private static SettlementMismatchRow mismatch(MismatchCheckType type, Long targetId,
                                                  MismatchResolutionType resolution) {
        return mismatch(type, targetId, 9800L, 9790L, resolution);
    }

    private static SettlementMismatchRow mismatch(MismatchCheckType type, Long targetId, long expected, long actual,
                                                  MismatchResolutionType resolution) {
        return new SettlementMismatchRow(MISMATCH, type, targetId, TARGET, expected, actual,
                resolution, 301L, NOW.minusDays(1), null, null);
    }

    // 예약액 1000 에 원장 소진 1300 이라 초과액은 300 이다
    private void openOverspend() {
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.of(
                mismatch(MismatchCheckType.CAMPAIGN_OVERSPEND, CAMPAIGN, 1000L, 1300L, null)));
    }

    // PG 가 승인한 금액이 pgApproved 인 결제 대상 건이다
    private void openPgMismatch(long pgApproved) {
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.of(
                mismatch(MismatchCheckType.PG_VS_CHARGE, PAYMENT, pgApproved, 10000L, null)));
        paymentOwnedBy(OWNER);
    }

    private void chargeOf(long amount, Long ownerId) {
        when(ledgerRepository.findByPaymentIdAndEntryType(PAYMENT, EntryType.CHARGE))
                .thenReturn(Optional.of(PointLedger.charge(ownerId, PAYMENT, amount, LedgerSource.REALTIME)));
    }

    private static CampaignInfo campaign(Long ownerId) {
        return new CampaignInfo(CAMPAIGN, ownerId, 3L, null, 0, null, null, null, null, null, null, 0, null, 0,
                null, List.of(), null, null, null, null, null);
    }

    private void paymentOwnedBy(Long ownerId) {
        Payment payment = mock(Payment.class);
        when(payment.getOwnerId()).thenReturn(ownerId);
        when(paymentRepository.findById(PAYMENT)).thenReturn(Optional.of(payment));
    }

    private void assertRejected(PointAdjustmentRequest request, ErrorCode expected) {
        assertThatThrownBy(() -> recorder.record(ADMIN, request))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private void assertNothingRecorded() {
        verify(ledgerRepository, never()).save(any());
        verify(mismatchRepository, never()).resolveByAdmin(any(), any(), any(), any());
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void 새_조정을_원장에_기록하고_감사_로그를_남긴다() {
        PointAdjustmentResult result = recorder.record(ADMIN, request(1000L, CAMPAIGN, null));

        ArgumentCaptor<PointLedger> saved = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgerRepository).save(saved.capture());
        assertThat(saved.getValue().getEntryType()).isEqualTo(EntryType.ADJUST);
        assertThat(saved.getValue().getSource()).isEqualTo(LedgerSource.MANUAL);
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.created()).isTrue();
        assertThat(result.adjustment().ledgerId()).isEqualTo(9101L);
        assertThat(result.adjustment().amount()).isEqualTo(1000L);
        assertThat(result.adjustment().campaignId()).isEqualTo(CAMPAIGN);
        assertThat(result.adjustment().createdAt().toString()).isEqualTo("2026-10-14T11:20+09:00");
        verify(auditLogWriter).write(ADMIN, PointAdjustmentRecorder.AUDIT_ACTION, "7",
                "ledgerId=9101;amount=1000;campaignId=12;mismatchId=null;requestId=req-1");
    }

    @Test
    void 계정_행이_없는_점주는_행을_만들어_잠근_뒤_기록한다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.empty());

        PointAdjustmentResult result = recorder.record(ADMIN, request(1000L, null, null));

        verify(accountRepository).createIfAbsent(OWNER, NOW);
        assertThat(result.created()).isTrue();
    }

    @Test
    void 잠금을_얻지_못하면_기록하지_않고_잠시_뒤_다시_시도하게_한다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertRejected(request(1000L, null, null), BillingErrorCode.LEDGER_UNAVAILABLE);

        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 같은_키로_같은_내용이_다시_오면_처음_기록을_돌려주고_새로_쓰지_않는다() {
        PointLedger first = persisted(PointLedger.adjust(OWNER, 1000L, REASON, CAMPAIGN, TARGET, KEY), 9001L);
        when(ledgerRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(first));

        /*
         * 대사 건은 다시 검사하지 않는다.
         * 처음 요청이 이미 닫았을 수 있다.
         */
        PointAdjustmentResult result = recorder.record(ADMIN, request(1000L, CAMPAIGN, MISMATCH));

        assertThat(result.created()).isFalse();
        assertThat(result.adjustment().ledgerId()).isEqualTo(9001L);
        assertNothingRecorded();
    }

    @Test
    void 같은_키로_금액이나_캠페인이나_사유가_다르면_거절한다() {
        PointLedger first = persisted(PointLedger.adjust(OWNER, 1000L, REASON, CAMPAIGN, TARGET, KEY), 9001L);
        when(ledgerRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(first));

        assertRejected(request(2000L, CAMPAIGN, null), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertRejected(request(1000L, null, null), BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertRejected(new PointAdjustmentRequest(KEY, OWNER, 1000L, "다른 사유", CAMPAIGN, TARGET, null),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void 정정_대상_영업일을_원장과_응답에_담는다() {
        PointAdjustmentResult result = recorder.record(ADMIN, request(1000L, CAMPAIGN, null));

        ArgumentCaptor<PointLedger> saved = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgerRepository).save(saved.capture());
        assertThat(saved.getValue().getAdjustTargetDate()).isEqualTo(TARGET);
        assertThat(result.adjustment().targetBusinessDate()).isEqualTo(TARGET);
    }

    @Test
    void 일반_조정도_없는_캠페인이나_다른_점주의_캠페인은_거절한다() {
        when(campaignQueryService.findAllById(List.of(CAMPAIGN))).thenReturn(List.of());

        assertRejected(request(1000L, CAMPAIGN, null), BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 같은_키로_정정_대상_영업일이_다르면_거절한다() {
        PointLedger first = persisted(PointLedger.adjust(OWNER, 1000L, REASON, CAMPAIGN, TARGET, KEY), 9001L);
        when(ledgerRepository.findByOwnerIdAndIdempotencyKey(OWNER, KEY)).thenReturn(Optional.of(first));

        assertRejected(new PointAdjustmentRequest(KEY, OWNER, 1000L, REASON, CAMPAIGN, TARGET.minusDays(1), null),
                BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    void 음수_조정_뒤_잔액이_음수가_되면_거절한다() {
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(499L);

        assertRejected(request(-500L, null, null), BillingErrorCode.ADJUSTMENT_MAKES_BALANCE_NEGATIVE);

        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 음수_조정_뒤_잔액이_0이면_기록한다() {
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(500L);

        assertThat(recorder.record(ADMIN, request(-500L, null, null)).created()).isTrue();
    }

    @Test
    void 대사_건이_없으면_거절한다() {
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.empty());

        assertRejected(request(1000L, null, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_NOT_FOUND);
    }

    @Test
    void 이미_해결된_대사_건이면_거절한다() {
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.of(
                mismatch(MismatchCheckType.PG_VS_CHARGE, PAYMENT, MismatchResolutionType.LEDGER_CORRECTION)));

        assertRejected(request(1000L, null, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED);
    }

    @Test
    void 결제_대상_대사_건이_다른_점주의_결제면_거절한다() {
        when(mismatchRepository.findRowById(MISMATCH))
                .thenReturn(Optional.of(mismatch(MismatchCheckType.PG_VS_CHARGE, PAYMENT, null)));
        paymentOwnedBy(OTHER_OWNER);

        assertRejected(request(1000L, null, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
    }

    @Test
    void 결제_대상_대사_건의_결제가_없으면_점주를_확인할_수_없어_거절한다() {
        when(mismatchRepository.findRowById(MISMATCH))
                .thenReturn(Optional.of(mismatch(MismatchCheckType.PG_VS_CHARGE, PAYMENT, null)));
        when(paymentRepository.findById(PAYMENT)).thenReturn(Optional.empty());

        assertRejected(request(1000L, null, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
    }

    @Test
    void 결제_대상_대사_건은_CHARGE가_없으면_PG_승인_금액만큼_양수로_닫는다() {
        openPgMismatch(50000L);
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(1);

        PointAdjustmentResult result = recorder.record(ADMIN, request(50000L, null, MISMATCH));

        assertThat(result.created()).isTrue();
        verify(mismatchRepository).resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW);
    }

    @Test
    void 결제_대상_대사_건은_승인되지_않은_결제의_CHARGE를_음수로_회수하며_닫는다() {
        openPgMismatch(0L);
        chargeOf(10000L, OWNER);
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(1);

        PointAdjustmentResult result = recorder.record(ADMIN, request(-10000L, null, MISMATCH));

        assertThat(result.created()).isTrue();
        assertThat(result.adjustment().amount()).isEqualTo(-10000L);
        verify(mismatchRepository).resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW);
        verify(auditLogWriter).write(ADMIN, PointAdjustmentRecorder.AUDIT_ACTION, "7",
                "ledgerId=9101;amount=-10000;campaignId=null;mismatchId=55;requestId=req-1");
    }

    @Test
    void 결제_대상_대사_건의_음수_조정은_잔액이_부족하면_원장도_대사_건도_기록하지_않는다() {
        openPgMismatch(0L);
        chargeOf(10000L, OWNER);
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(9999L);

        assertRejected(request(-10000L, null, MISMATCH), BillingErrorCode.ADJUSTMENT_MAKES_BALANCE_NEGATIVE);

        assertNothingRecorded();
    }

    @Test
    void 결제_대상_대사_건의_조정액이_PG_승인_금액에서_CHARGE를_뺀_값과_다르면_거절한다() {
        // 정정액은 9900 - 10000 = -100 이다
        openPgMismatch(9900L);
        chargeOf(10000L, OWNER);

        assertRejected(request(-500L, null, MISMATCH), CommonErrorCode.INVALID_INPUT);
        assertRejected(request(100L, null, MISMATCH), CommonErrorCode.INVALID_INPUT);

        assertNothingRecorded();
    }

    @Test
    void 결제_대상_대사_건은_다른_점주에게_들어간_CHARGE를_요청한_점주의_충전으로_보지_않는다() {
        openPgMismatch(10000L);
        chargeOf(10000L, OTHER_OWNER);
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(1);

        assertThat(recorder.record(ADMIN, request(10000L, null, MISMATCH)).created()).isTrue();
    }

    @Test
    void 결제_대상_대사_건은_캠페인을_붙여_조정할_수_없다() {
        openPgMismatch(50000L);

        assertRejected(request(50000L, CAMPAIGN, MISMATCH), CommonErrorCode.INVALID_INPUT);

        assertNothingRecorded();
    }

    @Test
    void 캠페인_대상_대사_건을_닫을_때_정정_대상_영업일이_대사_건의_영업일과_다르면_거절한다() {
        openOverspend();

        assertRejected(new PointAdjustmentRequest(KEY, OWNER, 300L, REASON, CAMPAIGN, TARGET.minusDays(1), MISMATCH),
                CommonErrorCode.INVALID_INPUT);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 캠페인_대상_대사_건이_다른_점주의_캠페인이면_거절한다() {
        openOverspend();
        when(campaignQueryService.findAllById(List.of(CAMPAIGN))).thenReturn(List.of(campaign(OTHER_OWNER)));

        // 결제로 보고 찾으면 대상 ID 가 캠페인 ID 라 엉뚱한 결제의 점주와 견주게 된다
        assertRejected(request(300L, CAMPAIGN, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
        verify(paymentRepository, never()).findById(any());
    }

    @Test
    void 캠페인_대상_대사_건의_캠페인이_없으면_거절한다() {
        openOverspend();
        when(campaignQueryService.findAllById(List.of(CAMPAIGN))).thenReturn(List.of());

        assertRejected(request(300L, CAMPAIGN, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
    }

    @Test
    void 캠페인_대상_대사_건을_닫을_때_campaignId가_없거나_다르면_거절한다() {
        openOverspend();

        assertRejected(request(300L, null, MISMATCH), CommonErrorCode.INVALID_INPUT);
        assertRejected(request(300L, 99L, MISMATCH), CommonErrorCode.INVALID_INPUT);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void 초과_소진_대사_건을_초과액_이하의_양수로_닫고_감사_로그에_대사_건을_남긴다() {
        openOverspend();
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(1);

        PointAdjustmentResult result = recorder.record(ADMIN, request(300L, CAMPAIGN, MISMATCH));

        assertThat(result.created()).isTrue();
        verify(mismatchRepository).resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW);
        verify(auditLogWriter).write(ADMIN, PointAdjustmentRecorder.AUDIT_ACTION, "7",
                "ledgerId=9101;amount=300;campaignId=12;mismatchId=55;requestId=req-1");
    }

    @Test
    void 초과_소진_대사_건은_초과액보다_적게_돌려줘도_닫는다() {
        openOverspend();
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(1);

        assertThat(recorder.record(ADMIN, request(100L, CAMPAIGN, MISMATCH)).created()).isTrue();
    }

    @Test
    void 초과_소진_대사_건에_초과액을_넘는_양수나_음수를_연결하면_거절한다() {
        openOverspend();

        assertRejected(request(301L, CAMPAIGN, MISMATCH), CommonErrorCode.INVALID_INPUT);
        assertRejected(request(-100L, CAMPAIGN, MISMATCH), CommonErrorCode.INVALID_INPUT);

        assertNothingRecorded();
    }

    @Test
    void 카운터_대_원장_대사_건에는_어느_방향이든_조정을_연결할_수_없다() {
        when(mismatchRepository.findRowById(MISMATCH)).thenReturn(Optional.of(
                mismatch(MismatchCheckType.CAMPAIGN_SPEND_VS_LEDGER, CAMPAIGN, 1000L, 1100L, null)));

        assertRejected(request(100L, CAMPAIGN, MISMATCH), CommonErrorCode.INVALID_INPUT);
        assertRejected(request(-100L, CAMPAIGN, MISMATCH), CommonErrorCode.INVALID_INPUT);

        assertNothingRecorded();
    }

    @Test
    void 대사_건을_읽은_뒤_다른_경로가_먼저_닫았으면_거절해_원장_기록까지_롤백하게_한다() {
        openPgMismatch(50000L);
        when(mismatchRepository.resolveByAdmin(MISMATCH, MismatchResolutionType.MANUAL_ADJUSTMENT, ADMIN, NOW))
                .thenReturn(0);

        assertRejected(request(50000L, null, MISMATCH), BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED);

        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }
}
