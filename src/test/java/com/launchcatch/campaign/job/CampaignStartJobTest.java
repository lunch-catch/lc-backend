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
class CampaignStartJobTest {

    @Mock
    private CampaignDailyTransitionService transitions;

    @InjectMocks
    private CampaignStartJob job;

    @Test
    @DisplayName("00:00 묶음의 둘째 단계로 등록되고 영업일로 집행 시작 전환을 부른다")
    void 둘째_단계로_집행_시작_전환을_부른다() {
        LocalDate businessDate = LocalDate.of(2026, 11, 1);

        job.run(businessDate);

        assertThat(job.jobName()).isEqualTo("CAMPAIGN_START_SCHEDULED");
        assertThat(CampaignStartJob.class.getAnnotation(Order.class).value()).isEqualTo(20);
        verify(transitions).startScheduled(businessDate);
    }
}
