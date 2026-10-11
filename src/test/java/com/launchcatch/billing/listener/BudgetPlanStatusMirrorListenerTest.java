package com.launchcatch.billing.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.launchcatch.billing.dto.BudgetPlanStatusChanged;
import com.launchcatch.billing.service.CampaignStatusService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BudgetPlanStatusMirrorListenerTest {

    @Test
    void 커밋_뒤_통보를_서비스의_해시_갱신으로_넘긴다() {
        CampaignStatusService service = mock(CampaignStatusService.class);
        BudgetPlanStatusChanged changed = new BudgetPlanStatusChanged(
                12L, LocalDate.of(2026, 10, 1), true, LocalDateTime.of(2026, 10, 1, 11, 0));

        new BudgetPlanStatusMirrorListener(service).on(changed);

        verify(service).mirror(changed);
    }
}
