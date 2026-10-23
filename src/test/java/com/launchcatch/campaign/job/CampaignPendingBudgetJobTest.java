package com.launchcatch.campaign.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.launchcatch.campaign.service.CampaignDailyTransitionService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

@ExtendWith(MockitoExtension.class)
class CampaignPendingBudgetJobTest {

    @Mock
    private CampaignDailyTransitionService transitions;

    @InjectMocks
    private CampaignPendingBudgetJob job;

    @Test
    @DisplayName("정산의 첫 단계(40) 앞인 35번째 단계로 등록되고 영업일로 예산 적용을 부른다")
    void 정산_앞_단계로_예산_적용을_부른다() {
        LocalDate businessDate = LocalDate.of(2026, 11, 1);

        job.run(businessDate);

        assertThat(job.jobName()).isEqualTo("CAMPAIGN_APPLY_PENDING_BUDGET");
        assertThat(CampaignPendingBudgetJob.class.getAnnotation(Order.class).value()).isEqualTo(35);
        verify(transitions).applyPendingDailyBudgets(businessDate);
    }
}
