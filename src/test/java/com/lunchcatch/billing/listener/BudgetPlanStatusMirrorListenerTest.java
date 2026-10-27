package com.lunchcatch.billing.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lunchcatch.billing.dto.BudgetPlanStatusChanged;
import com.lunchcatch.billing.dto.BudgetPlanStatusConfirmed;
import com.lunchcatch.billing.dto.RechargeReserveIncreased;
import com.lunchcatch.billing.service.BudgetPlanStatusService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BudgetPlanStatusMirrorListenerTest {

    @Test
    void 커밋_뒤_통보를_서비스의_해시_갱신으로_넘긴다() {
        BudgetPlanStatusService service = mock(BudgetPlanStatusService.class);
        BudgetPlanStatusChanged changed = new BudgetPlanStatusChanged(
                12L, LocalDate.of(2026, 10, 1), true, LocalDateTime.of(2026, 10, 1, 11, 0));

        new BudgetPlanStatusMirrorListener(service).on(changed);

        verify(service).mirror(changed);
    }

    @Test
    void 예약의_상태_확인_통보는_해시_지우기로_넘긴다() {
        BudgetPlanStatusService service = mock(BudgetPlanStatusService.class);
        BudgetPlanStatusConfirmed confirmed = new BudgetPlanStatusConfirmed(12L, LocalDate.of(2026, 10, 1));

        new BudgetPlanStatusMirrorListener(service).on(confirmed);

        verify(service).dropMirror(confirmed);
    }

    @Test
    void 재예약이_늘린_예약액_통보는_해시_반영으로_넘긴다() {
        BudgetPlanStatusService service = mock(BudgetPlanStatusService.class);
        RechargeReserveIncreased increased = new RechargeReserveIncreased(12L, LocalDate.of(2026, 10, 1), 10000L);

        new BudgetPlanStatusMirrorListener(service).on(increased);

        verify(service).mirrorReserve(increased);
    }
}
