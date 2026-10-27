package com.lunchcatch.campaign.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.lunchcatch.campaign.service.CampaignStoreSummaryService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;

@ExtendWith(MockitoExtension.class)
class CampaignStoreSummaryJobTest {

    @Mock
    private CampaignStoreSummaryService storeSummaries;

    @InjectMocks
    private CampaignStoreSummaryJob job;

    @Test
    @DisplayName("전환(10, 20) 뒤 30번째 단계로 등록되고 영업일로 요약 준비를 부른다")
    void 전환_뒤_단계로_요약_준비를_부른다() {
        LocalDate businessDate = LocalDate.of(2026, 11, 1);

        job.run(businessDate);

        assertThat(job.jobName()).isEqualTo("CAMPAIGN_STORE_SUMMARY");
        assertThat(CampaignStoreSummaryJob.class.getAnnotation(Order.class).value()).isEqualTo(30);
        verify(storeSummaries).prepare(businessDate);
    }
}
