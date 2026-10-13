package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import com.launchcatch.billing.repository.DailySpend;
import com.launchcatch.billing.repository.DailySpendHashRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BudgetQueryServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 14, 11, 30);
    private static final BillingScheduleProperties SCHEDULE =
            new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(34, 33, 33));

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private BudgetHourlyTargetRepository targetRepository;
    @Mock
    private DailySpendHashRepository hashRepository;
    @Mock
    private SpendHashRebuildService rebuildService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private BudgetQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BudgetQueryServiceImpl(planRepository, targetRepository, hashRepository, rebuildService,
                SCHEDULE, meterRegistry);
    }

    private static BudgetDailyPlan plan(long planId, long campaignId, long unitPrice) {
        BudgetDailyPlan plan = BeanUtils.instantiateClass(BudgetDailyPlan.class);
        ReflectionTestUtils.setField(plan, "id", planId);
        ReflectionTestUtils.setField(plan, "campaignId", campaignId);
        ReflectionTestUtils.setField(plan, "unitPrice", unitPrice);
        return plan;
    }

    private static BudgetHourlyTarget target(long planId, int hour, long amount) {
        BudgetHourlyTarget target = BeanUtils.instantiateClass(BudgetHourlyTarget.class);
        ReflectionTestUtils.setField(target, "budgetDailyPlanId", planId);
        ReflectionTestUtils.setField(target, "hour", (byte) hour);
        ReflectionTestUtils.setField(target, "targetAmount", amount);
        return target;
    }

    private double fallbackCount() {
        return meterRegistry.counter(BudgetQueryServiceImpl.FALLBACK_METRIC).count();
    }

    @Test
    void 여러_캠페인을_한_번의_조회로_계산하고_요청_순서를_지킨다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(2L, 1L)))
                .thenReturn(List.of(plan(101, 1, 10), plan(102, 2, 20)));
        when(hashRepository.readAll(DAY, List.of(2L, 1L)))
                .thenReturn(Map.of(1L, new DailySpend(10_000L, 6_060L), 2L, new DailySpend(10_000L, 0L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(102L, 101L)))
                .thenReturn(List.of(target(101, 10, 3400), target(101, 11, 3300), target(101, 12, 3300)));

        List<BudgetProgress> result = service.getProgress(List.of(2L, 1L), DAY, AT);

        assertThat(result).extracting(BudgetProgress::campaignId).containsExactly(2L, 1L);
        BudgetProgress first = result.get(1);
        assertThat(first.reservedAmount()).isEqualTo(10_000L);
        assertThat(first.unitPrice()).isEqualTo(10L);
        assertThat(first.todaySpend()).isEqualTo(6_060L);
        assertThat(first.plannedAmountUntil()).isEqualTo(5_050L);
        assertThat(first.progressRate()).isEqualTo(1.2);
        verify(planRepository, times(1)).findByBusinessDateAndCampaignIdIn(any(), any());
        verify(hashRepository, times(1)).readAll(any(), any());
        verify(targetRepository, times(1)).findByBudgetDailyPlanIdIn(any());
        verify(rebuildService, never()).rebuildIfAbsent(any(), any());
    }

    @Test
    void 목표_행이_없으면_가중치로_같은_값을_계산하고_지표를_올린다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(2L))).thenReturn(List.of(plan(102, 2, 20)));
        when(hashRepository.readAll(DAY, List.of(2L))).thenReturn(Map.of(2L, new DailySpend(10_000L, 0L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(102L))).thenReturn(List.of());

        BudgetProgress progress = service.getProgress(List.of(2L), DAY, AT).get(0);

        assertThat(progress.plannedAmountUntil()).isEqualTo(5_050L);
        assertThat(progress.progressRate()).isZero();
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    void 그날_계획이_없는_캠페인은_결과에서_빠지고_모두_없으면_해시를_읽지_않는다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(9L))).thenReturn(List.of());

        assertThat(service.getProgress(List.of(9L), DAY, AT)).isEmpty();
        verifyNoInteractions(hashRepository, targetRepository, rebuildService);
    }

    @Test
    void 해시가_없는_캠페인만_재구성하고_그_캠페인만_다시_읽는다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L, 2L)))
                .thenReturn(List.of(plan(101, 1, 10), plan(102, 2, 20)));
        when(hashRepository.readAll(DAY, List.of(1L, 2L))).thenReturn(Map.of(1L, new DailySpend(10_000L, 0L)));
        when(hashRepository.readAll(DAY, List.of(2L))).thenReturn(Map.of(2L, new DailySpend(5_000L, 100L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(101L, 102L))).thenReturn(List.of());

        List<BudgetProgress> result = service.getProgress(List.of(1L, 2L), DAY, AT);

        assertThat(result).extracting(BudgetProgress::reservedAmount).containsExactly(10_000L, 5_000L);
        verify(rebuildService).rebuildIfAbsent(DAY, 2L);
        verify(rebuildService, never()).rebuildIfAbsent(DAY, 1L);
    }

    @Test
    void 재구성한_뒤에도_해시가_없으면_예외다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of(plan(101, 1, 10)));
        when(hashRepository.readAll(DAY, List.of(1L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getProgress(List.of(1L), DAY, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=1");
        verify(rebuildService).rebuildIfAbsent(DAY, 1L);
    }

    @Test
    void Valkey_장애는_대체값_없이_그대로_올라온다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of(plan(101, 1, 10)));
        when(hashRepository.readAll(DAY, List.of(1L))).thenThrow(new QueryTimeoutException("timeout"));

        assertThatThrownBy(() -> service.getProgress(List.of(1L), DAY, AT))
                .isInstanceOf(QueryTimeoutException.class);
    }
}
