package com.lunchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.campaign.repository.CampaignStatusLogRepository;
import com.lunchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignStatsQueryServiceImplTest {

    // 테스트의 "오늘"을 2026-10-16(KST)으로 고정한다.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ClockConfig.ZONE);

    @Mock
    private CampaignStatusLogRepository campaignStatusLogRepository;

    private CampaignStatsQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CampaignStatsQueryServiceImpl(campaignStatusLogRepository, CLOCK);
    }

    @Test
    void 영업일_다음_날_00시_직전을_기준으로_위임하고_반환값을_그대로_돌려준다() {
        LocalDate businessDate = LocalDate.of(2026, 10, 15);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 10, 16, 0, 0, 0);
        when(campaignStatusLogRepository.countActiveAsOf(endExclusive)).thenReturn(3L);

        long count = service.activeCampaignCount(businessDate);

        assertThat(count).isEqualTo(3L);
        verify(campaignStatusLogRepository).countActiveAsOf(endExclusive);
    }

    @Test
    void 오늘을_넣으면_거부한다() {
        LocalDate today = LocalDate.of(2026, 10, 16);

        assertThatThrownBy(() -> service.activeCampaignCount(today))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 미래_날짜를_넣으면_거부한다() {
        LocalDate tomorrow = LocalDate.of(2026, 10, 17);

        assertThatThrownBy(() -> service.activeCampaignCount(tomorrow))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
