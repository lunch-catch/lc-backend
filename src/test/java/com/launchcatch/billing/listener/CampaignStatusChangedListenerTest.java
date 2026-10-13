package com.launchcatch.billing.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.launchcatch.billing.service.BudgetPlanStatusService;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CampaignStatusChangedListenerTest {

    @Test
    void 받은_상태_변경을_서비스에_그대로_넘긴다() {
        BudgetPlanStatusService service = mock(BudgetPlanStatusService.class);
        CampaignStatusChangedEvent event = new CampaignStatusChangedEvent(
                12L, LocalDate.of(2026, 10, 1), false, LocalDateTime.of(2026, 10, 1, 11, 0));

        new CampaignStatusChangedListener(service).on(event);

        verify(service).apply(event);
    }
}
