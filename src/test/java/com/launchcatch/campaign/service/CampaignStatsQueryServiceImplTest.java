package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignStatsQueryServiceImplTest {

    @Mock
    private CampaignStatusLogRepository campaignStatusLogRepository;

    private CampaignStatsQueryServiceImpl service;

    @Test
    void 영업일_다음_날_00시_직전을_기준으로_위임하고_반환값을_그대로_돌려준다() {
        service = new CampaignStatsQueryServiceImpl(campaignStatusLogRepository);
        LocalDate businessDate = LocalDate.of(2026, 10, 15);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 10, 16, 0, 0, 0);
        when(campaignStatusLogRepository.countActiveAsOf(endExclusive)).thenReturn(3L);

        long count = service.activeCampaignCount(businessDate);

        assertThat(count).isEqualTo(3L);
        verify(campaignStatusLogRepository).countActiveAsOf(endExclusive);
    }
}
