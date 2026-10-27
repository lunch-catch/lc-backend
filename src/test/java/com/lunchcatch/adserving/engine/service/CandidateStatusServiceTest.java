package com.lunchcatch.adserving.engine.service;

import com.lunchcatch.adserving.engine.repository.CandidateChangeNotifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.engine.entity.CandidateCampaignState;
import com.lunchcatch.adserving.engine.repository.AdCandidateRepository;
import com.lunchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("후보 서빙 상태 반영")
class CandidateStatusServiceTest {

    private static final Long CAMPAIGN_ID = 101L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 11, 3);
    /*
     * 반영한 시각은 전이 시각보다 늦다.
     * 둘을 갈라 전이 시각을 그대로 쓰는지 본다.
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 11, 5);

    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateChangeNotifier notifier = org.mockito.Mockito.mock(CandidateChangeNotifier.class);
    private final CandidateStatusService service = new CandidateStatusService(adCandidateRepository, notifier, meterRegistry,
            Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));

    private double missingCount() {
        return meterRegistry.counter(CandidateStatusService.CANDIDATE_MISSING_METRIC).count();
    }

    @Test
    void 서빙할_수_없게_되면_그날_후보를_PAUSED_로_바꾼다() {
        when(adCandidateRepository.changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.PAUSED.name(), CHANGED_AT, NOW))
                .thenReturn(1);

        service.changeServable(CAMPAIGN_ID, BUSINESS_DATE, false, CHANGED_AT);

        verify(adCandidateRepository).changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.PAUSED.name(), CHANGED_AT, NOW);
        verify(notifier).changed(BUSINESS_DATE, CAMPAIGN_ID);
        assertThat(missingCount()).isZero();
    }

    @Test
    void 다시_서빙할_수_있게_되면_SERVABLE_로_바꾼다() {
        when(adCandidateRepository.changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.SERVABLE.name(), CHANGED_AT, NOW))
                .thenReturn(1);

        service.changeServable(CAMPAIGN_ID, BUSINESS_DATE, true, CHANGED_AT);

        verify(adCandidateRepository).changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.SERVABLE.name(), CHANGED_AT, NOW);
    }

    /*
     * 더 늦은 전이가 이미 반영된 후보다.
     * 순서가 바뀌어 온 이벤트라 세지 않는다.
     */
    @Test
    void 이미_더_늦은_전이가_반영됐으면_넘어가고_세지도_알리지도_않는다() {
        org.mockito.Mockito.doThrow(new AssertionError("바뀐 행이 없는데 알렸다")).when(notifier).changed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(adCandidateRepository.changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.PAUSED.name(), CHANGED_AT, NOW))
                .thenReturn(0);
        when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, CAMPAIGN_ID)).thenReturn(true);

        service.changeServable(CAMPAIGN_ID, BUSINESS_DATE, false, CHANGED_AT);

        assertThat(missingCount()).isZero();
    }

    @Test
    void 그_영업일의_후보가_없으면_예외_없이_넘어가고_센다() {
        when(adCandidateRepository.changeState(BUSINESS_DATE, CAMPAIGN_ID, CandidateCampaignState.PAUSED.name(), CHANGED_AT, NOW))
                .thenReturn(0);
        when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, CAMPAIGN_ID)).thenReturn(false);

        service.changeServable(CAMPAIGN_ID, BUSINESS_DATE, false, CHANGED_AT);

        assertThat(missingCount()).isEqualTo(1);
    }
}
