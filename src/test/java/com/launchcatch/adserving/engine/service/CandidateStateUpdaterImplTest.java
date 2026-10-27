package com.launchcatch.adserving.engine.service;

import com.launchcatch.adserving.engine.repository.CandidateChangeNotifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("후보 소진 반영")
class CandidateStateUpdaterImplTest {

    private static final Long CAMPAIGN_ID = 101L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime SOLD_OUT_AT = LocalDateTime.of(2026, 10, 1, 11, 3, 20);
    // 소진 반영을 부른 시각은 소진 시각보다 늦다. 둘을 갈라 소진 시각을 바꾸지 않는지 본다
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 11, 5);

    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateChangeNotifier notifier = org.mockito.Mockito.mock(CandidateChangeNotifier.class);
    private final CandidateStateUpdaterImpl updater = new CandidateStateUpdaterImpl(adCandidateRepository, notifier, meterRegistry,
            Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));

    private double missingCount() {
        return meterRegistry.counter(CandidateStateUpdaterImpl.CANDIDATE_MISSING_METRIC).count();
    }

    @Test
    void 받은_캠페인과_영업일의_후보에_받은_소진_시각을_쓴다() {
        when(adCandidateRepository.markSoldOut(BUSINESS_DATE, CAMPAIGN_ID, SOLD_OUT_AT, NOW)).thenReturn(1);

        updater.markSoldOut(CAMPAIGN_ID, BUSINESS_DATE, SOLD_OUT_AT);

        verify(adCandidateRepository).markSoldOut(BUSINESS_DATE, CAMPAIGN_ID, SOLD_OUT_AT, NOW);
        // 다른 서버의 메모리 후보도 소진을 알게 한다(#1042)
        verify(notifier).changed(BUSINESS_DATE, CAMPAIGN_ID);
        verify(adCandidateRepository, never()).existsByIdBusinessDateAndIdCampaignId(any(), any());
        assertThat(missingCount()).isZero();
    }

    @Test
    void 이미_소진된_후보면_예외_없이_넘어가고_세지도_알리지도_않는다() {
        when(adCandidateRepository.markSoldOut(BUSINESS_DATE, CAMPAIGN_ID, SOLD_OUT_AT, NOW)).thenReturn(0);
        org.mockito.Mockito.doThrow(new AssertionError("바뀐 행이 없는데 알렸다")).when(notifier).changed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, CAMPAIGN_ID)).thenReturn(true);

        updater.markSoldOut(CAMPAIGN_ID, BUSINESS_DATE, SOLD_OUT_AT);

        assertThat(missingCount()).isZero();
    }

    @Test
    void 그_영업일의_후보가_없으면_예외_없이_넘어가고_센다() {
        when(adCandidateRepository.markSoldOut(BUSINESS_DATE, CAMPAIGN_ID, SOLD_OUT_AT, NOW)).thenReturn(0);
        when(adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(BUSINESS_DATE, CAMPAIGN_ID)).thenReturn(false);

        updater.markSoldOut(CAMPAIGN_ID, BUSINESS_DATE, SOLD_OUT_AT);

        assertThat(missingCount()).isEqualTo(1);
    }
}
