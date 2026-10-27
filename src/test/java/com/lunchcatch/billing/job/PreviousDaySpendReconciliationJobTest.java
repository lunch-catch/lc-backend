package com.lunchcatch.billing.job;

import static org.mockito.Mockito.inOrder;

import com.lunchcatch.billing.service.SpendReconciliationService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreviousDaySpendReconciliationJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Mock SpendReconciliationService reconciliation;

    @Test
    void 전날_대조에_이어_해제_뒤_등식을_대조한다() {
        new PreviousDaySpendReconciliationJob(reconciliation).run(DAY);

        InOrder order = inOrder(reconciliation);
        order.verify(reconciliation).reconcilePreviousDay(DAY, PreviousDaySpendReconciliationJob.JOB_NAME);
        order.verify(reconciliation).reconcileReleasedDay(DAY, PreviousDaySpendReconciliationJob.JOB_NAME);
    }
}
