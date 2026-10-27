package com.lunchcatch.campaign.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.lunchcatch.campaign.service.CampaignDailyTransitionService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

@ExtendWith(MockitoExtension.class)
class CampaignEndJobTest {

    @Mock
    private CampaignDailyTransitionService transitions;

    @InjectMocks
    private CampaignEndJob job;

    @Test
    @DisplayName("00:00 묶음의 첫 단계로 등록되고 영업일로 종료 전환을 부른다")
    void 첫_단계로_종료_전환을_부른다() {
        LocalDate businessDate = LocalDate.of(2026, 11, 1);

        job.run(businessDate);

        assertThat(job.jobName()).isEqualTo("CAMPAIGN_END_EXPIRED");
        assertThat(CampaignEndJob.class.getAnnotation(Order.class).value()).isEqualTo(10);
        verify(transitions).endExpired(businessDate);
    }
}
