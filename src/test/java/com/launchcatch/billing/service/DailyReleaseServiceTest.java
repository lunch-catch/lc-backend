package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyReleaseServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final String JOB = "BILLING_DAILY_RELEASE";

    @Mock
    private SpendReconciliationService reconciliation;
    @Mock
    private DailyReservationService reservation;

    private DailyReleaseService service;

    @BeforeEach
    void setUp() {
        service = new DailyReleaseService(reconciliation, reservation);
    }

    @Test
    void 대조한_뒤_그_순서로_해제한다() {
        service.reconcileAndRelease(DAY, JOB);

        InOrder order = inOrder(reconciliation, reservation);
        order.verify(reconciliation).reconcileToday(DAY, JOB);
        order.verify(reservation).release(DAY);
    }

    @Test
    void 대조가_실패하면_해제하지_않는다() {
        IllegalStateException failure = new IllegalStateException("대조 실패");
        doThrow(failure).when(reconciliation).reconcileToday(DAY, JOB);

        assertThatThrownBy(() -> service.reconcileAndRelease(DAY, JOB)).isSameAs(failure);
        verifyNoInteractions(reservation);
    }
}
