package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.BudgetPlanStatusChanged;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;

class BudgetPlanStatusServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 11, 0);
    // 2026-10-01 11:00:01 KST
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 11, 0, 1);

    private final BudgetDailyPlanRepository planRepository = mock(BudgetDailyPlanRepository.class);
    private final DailySpendCounter spendCounter = mock(DailySpendCounter.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T02:00:01Z"), ClockConfig.ZONE);
    private final BudgetPlanStatusService service =
            new BudgetPlanStatusService(planRepository, spendCounter, publisher, clock);

    @Test
    void 서빙_불가로_바뀌면_중단으로_저장하고_커밋_뒤_해시_갱신을_통보한다() {
        when(planRepository.applyStatus(12L, DAY, true, CHANGED_AT, NOW)).thenReturn(1);

        service.apply(new CampaignStatusChangedEvent(12L, DAY, false, CHANGED_AT));

        verify(publisher).publishEvent(new BudgetPlanStatusChanged(12L, DAY, true, CHANGED_AT));
    }

    @Test
    void 서빙_가능으로_바뀌면_중단을_푼다() {
        when(planRepository.applyStatus(12L, DAY, false, CHANGED_AT, NOW)).thenReturn(1);

        service.apply(new CampaignStatusChangedEvent(12L, DAY, true, CHANGED_AT));

        verify(publisher).publishEvent(new BudgetPlanStatusChanged(12L, DAY, false, CHANGED_AT));
    }

    @Test
    void 예약의_상태_재확인도_같은_조건부_갱신을_쓰고_바꿨는지_돌려준다() {
        when(planRepository.applyStatus(12L, DAY, true, CHANGED_AT, NOW)).thenReturn(1);
        when(planRepository.applyStatus(13L, DAY, true, CHANGED_AT, NOW)).thenReturn(0);

        assertThat(service.applyStatus(12L, DAY, true, CHANGED_AT)).isTrue();
        assertThat(service.applyStatus(13L, DAY, true, CHANGED_AT)).isFalse();

        verify(publisher).publishEvent(new BudgetPlanStatusChanged(12L, DAY, true, CHANGED_AT));
        verify(publisher, never()).publishEvent(new BudgetPlanStatusChanged(13L, DAY, true, CHANGED_AT));
    }

    @Test
    void 상태_변경_시각이_없으면_견줄_수_없어_바꾸지_않는다() {
        assertThat(service.applyStatus(12L, DAY, true, null)).isFalse();

        verify(planRepository, never()).applyStatus(any(), any(), anyBoolean(), any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 행이_없거나_더_늦은_변경이_이미_있으면_통보하지_않는다() {
        when(planRepository.applyStatus(12L, DAY, true, CHANGED_AT, NOW)).thenReturn(0);

        service.apply(new CampaignStatusChangedEvent(12L, DAY, false, CHANGED_AT));

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void 커밋_뒤에는_해시의_중단_표시를_맞춘다() {
        service.mirror(new BudgetPlanStatusChanged(12L, DAY, true, CHANGED_AT));

        verify(spendCounter).applyStatus(DAY, 12L, true, CHANGED_AT);
        verify(spendCounter, never()).evict(any(), any());
    }

    @Test
    void 해시_갱신이_실패하면_던지지_않고_해시를_지운다() {
        when(spendCounter.applyStatus(any(), any(), anyBoolean(), any()))
                .thenThrow(new RedisConnectionFailureException("valkey down"));

        assertThatCode(() -> service.mirror(new BudgetPlanStatusChanged(12L, DAY, true, CHANGED_AT)))
                .doesNotThrowAnyException();

        verify(spendCounter).evict(DAY, 12L);
    }

    @Test
    void 해시를_지우는_것까지_실패해도_던지지_않는다() {
        when(spendCounter.applyStatus(any(), any(), anyBoolean(), any()))
                .thenThrow(new RedisConnectionFailureException("valkey down"));
        doThrow(new RedisConnectionFailureException("valkey down")).when(spendCounter).evict(DAY, 12L);

        assertThatCode(() -> service.mirror(new BudgetPlanStatusChanged(12L, DAY, true, CHANGED_AT)))
                .doesNotThrowAnyException();
    }
}
