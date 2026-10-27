package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.entity.Payment;
import com.lunchcatch.billing.entity.PgChargeReconciliationPending;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.PgChargeReconciliationPendingRepository;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class PgChargeMismatchRecorderTest {

    private static final LocalDate PREV = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 0, 0);
    private static final Long PAYMENT_ID = 101L;
    private static final Long OWNER_ID = 7L;
    private static final long AMOUNT = 30_000L;
    private static final Long RUN_ID = 42L;

    private final PointLedgerRepository ledgerRepository = mock(PointLedgerRepository.class);
    private final SettlementMismatchRepository mismatchRepository = mock(SettlementMismatchRepository.class);
    private final PgChargeReconciliationPendingRepository pendingRepository = mock(PgChargeReconciliationPendingRepository.class);
    private final PgChargeMismatchRecorder recorder =
            new PgChargeMismatchRecorder(ledgerRepository, mismatchRepository, pendingRepository);

    private static Payment payment() {
        Payment payment = Payment.order(OWNER_ID, "ord_20261015_abc", AMOUNT);
        ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
        return payment;
    }

    @Test
    void 빠진_CHARGE를_기록하고_불일치를_LEDGER_CORRECTION으로_닫아_기록한다() {
        recorder.recordMissingCharge(payment(), PREV, RUN_ID, NOW);

        ArgumentCaptor<PointLedger> captor = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgerRepository).save(captor.capture());
        PointLedger saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(saved.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(saved.getAmount()).isEqualTo(AMOUNT);
        assertThat(saved.getEntryType()).isEqualTo(EntryType.CHARGE);
        assertThat(saved.getSource()).isEqualTo(LedgerSource.RECONCILIATION);

        verify(mismatchRepository).insertPgChargeMismatchResolvedIfAbsent(PREV, PAYMENT_ID, AMOUNT, 0L, RUN_ID, NOW);
        verify(pendingRepository).deleteByPaymentId(PAYMENT_ID);
    }

    @Test
    void 미해결_불일치만_기록한다() {
        recorder.recordMismatch(PAYMENT_ID, 900L, 1000L, PREV, RUN_ID, NOW);

        verify(mismatchRepository).insertPgChargeMismatchIfAbsent(PREV, PAYMENT_ID, 900L, 1000L, RUN_ID, NOW);
        verify(pendingRepository).deleteByPaymentId(PAYMENT_ID);
    }

    @Test
    void 재검증_대기_행이_없으면_새로_남긴다() {
        when(pendingRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(false);

        recorder.recordPending(PAYMENT_ID, PREV, "PG inquiry failed");

        ArgumentCaptor<PgChargeReconciliationPending> captor =
                ArgumentCaptor.forClass(PgChargeReconciliationPending.class);
        verify(pendingRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(captor.getValue().getBusinessDate()).isEqualTo(PREV);
        assertThat(captor.getValue().getLastError()).isEqualTo("PG inquiry failed");
    }

    @Test
    void 재검증_대기_행이_이미_있으면_다시_남기지_않는다() {
        when(pendingRepository.existsByPaymentId(PAYMENT_ID)).thenReturn(true);

        recorder.recordPending(PAYMENT_ID, PREV, "PG inquiry failed");

        verify(pendingRepository, never()).save(any());
    }

    @Test
    void 재검증_대기_행을_해소한다() {
        recorder.resolvePending(PAYMENT_ID);

        verify(pendingRepository).deleteByPaymentId(PAYMENT_ID);
    }

    @Test
    void 두_기록_메서드_모두_트랜잭션_하나다() throws Exception {
        assertThat(PgChargeMismatchRecorder.class
                .getMethod("recordMissingCharge", Payment.class, LocalDate.class, Long.class, LocalDateTime.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(PgChargeMismatchRecorder.class
                .getMethod("recordMismatch", Long.class, long.class, long.class, LocalDate.class, Long.class,
                        LocalDateTime.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
