package com.launchcatch.adserving.engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.engine.repository.CandidateBudget;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("후보 예산 갱신")
class CandidateBudgetServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 11, 10);

    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final BudgetQueryService budgetQueryService = mock(BudgetQueryService.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CandidateBudgetService service = new CandidateBudgetService(adCandidateRepository, budgetQueryService,
            meterRegistry, Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE));

    @Test
    void 그날_후보마다_정산의_예약액과_단가를_덮고_계획이_없으면_0으로_둔다() {
        when(adCandidateRepository.findCampaignIdsByBusinessDate(BUSINESS_DATE)).thenReturn(List.of(1L, 2L));
        when(budgetQueryService.getProgress(List.of(1L, 2L), BUSINESS_DATE))
                .thenReturn(List.of(new BudgetProgress(1L, 24_000, 10, 500)));

        int updated = service.refresh(BUSINESS_DATE);

        assertThat(updated).isEqualTo(2);
        verify(adCandidateRepository).updateBudgets(BUSINESS_DATE,
                List.of(new CandidateBudget(1L, 24_000, 10), new CandidateBudget(2L, 0, 0)), NOW);
    }

    @Test
    void 후보가_정산이_한_번에_받는_수보다_많으면_나눠_부른다() {
        List<Long> many = LongStream.rangeClosed(1, BudgetQueryService.MAX_CAMPAIGN_IDS + 1L).boxed().toList();
        when(adCandidateRepository.findCampaignIdsByBusinessDate(BUSINESS_DATE)).thenReturn(many);
        when(budgetQueryService.getProgress(anyCollection(), eq(BUSINESS_DATE))).thenReturn(List.of());

        service.refresh(BUSINESS_DATE);

        verify(budgetQueryService, times(2)).getProgress(anyCollection(), eq(BUSINESS_DATE));
    }

    @Test
    void 그날_후보가_없으면_정산을_부르지_않는다() {
        when(adCandidateRepository.findCampaignIdsByBusinessDate(BUSINESS_DATE)).thenReturn(List.of());

        assertThat(service.refresh(BUSINESS_DATE)).isZero();
        verifyNoInteractions(budgetQueryService);
    }

    @Test
    void 정산을_읽지_못하면_값을_바꾸지_않고_센다() {
        when(adCandidateRepository.findCampaignIdsByBusinessDate(BUSINESS_DATE)).thenReturn(List.of(1L));
        when(budgetQueryService.getProgress(any(), any())).thenThrow(new IllegalStateException("카운터 장애"));

        assertThat(service.refresh(BUSINESS_DATE)).isZero();
        verify(adCandidateRepository, never()).updateBudgets(any(), any(), any());
        assertThat(meterRegistry.counter(CandidateBudgetService.BUDGET_UNAVAILABLE_METRIC).count()).isEqualTo(1);
    }
}
