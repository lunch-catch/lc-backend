package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HourlyTargetServiceTest {

    @Mock
    private BudgetHourlyTargetRepository targetRepository;

    private HourlyTargetService service;

    @BeforeEach
    void setUp() {
        service = new HourlyTargetService(targetRepository, Caffeine.newBuilder().build());
    }

    private static BudgetHourlyTarget target(long planId, int hour, long amount) {
        BudgetHourlyTarget target = BeanUtils.instantiateClass(BudgetHourlyTarget.class);
        ReflectionTestUtils.setField(target, "budgetDailyPlanId", planId);
        ReflectionTestUtils.setField(target, "hour", (byte) hour);
        ReflectionTestUtils.setField(target, "targetAmount", amount);
        return target;
    }

    @Test
    void 계획별로_시간대_목표를_묶어_한_번의_쿼리로_읽는다() {
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(101L, 102L))).thenReturn(List.of(
                target(101L, 10, 3400L), target(101L, 11, 3300L), target(102L, 10, 500L)));

        Map<Long, Map<Integer, Long>> result = service.findAll(List.of(101L, 102L));

        assertThat(result.get(101L)).containsEntry(10, 3400L).containsEntry(11, 3300L);
        assertThat(result.get(102L)).containsOnly(Map.entry(10, 500L));
        verify(targetRepository, times(1)).findByBudgetDailyPlanIdIn(List.of(101L, 102L));
    }

    @Test
    void 읽은_계획은_다음부터_캐시에서_답하고_없는_계획만_다시_읽는다() {
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(101L))).thenReturn(List.of(target(101L, 10, 3400L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(102L))).thenReturn(List.of(target(102L, 10, 500L)));
        service.findAll(List.of(101L));

        Map<Long, Map<Integer, Long>> result = service.findAll(List.of(101L, 102L));

        assertThat(result).containsOnlyKeys(101L, 102L);
        verify(targetRepository, times(1)).findByBudgetDailyPlanIdIn(List.of(101L));
        verify(targetRepository, times(1)).findByBudgetDailyPlanIdIn(List.of(102L));
    }

    @Test
    void 모두_캐시에_있으면_저장소를_부르지_않는다() {
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(101L))).thenReturn(List.of(target(101L, 10, 3400L)));
        service.findAll(List.of(101L));

        service.findAll(List.of(101L));

        verify(targetRepository, times(1)).findByBudgetDailyPlanIdIn(List.of(101L));
    }

    @Test
    void 목표_행이_없는_계획은_결과에_없고_캐시하지_않는다() {
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(101L))).thenReturn(List.of());

        assertThat(service.findAll(List.of(101L))).isEmpty();
        assertThat(service.findAll(List.of(101L))).isEmpty();
        verify(targetRepository, times(2)).findByBudgetDailyPlanIdIn(List.of(101L));
    }

    @Test
    void 계획이_없으면_쿼리하지_않는다() {
        assertThat(service.findAll(List.of())).isEmpty();
        verify(targetRepository, never()).findByBudgetDailyPlanIdIn(org.mockito.ArgumentMatchers.anyCollection());
    }
}
