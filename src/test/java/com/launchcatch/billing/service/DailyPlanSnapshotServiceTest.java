package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DailyPlanSnapshotServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    @Mock
    private BudgetDailyPlanRepository planRepository;

    private DailyPlanSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new DailyPlanSnapshotService(planRepository, Caffeine.newBuilder().build());
    }

    private static BudgetDailyPlan plan(Long ownerId, long unitPrice) {
        BudgetDailyPlan plan = BeanUtils.instantiateClass(BudgetDailyPlan.class);
        ReflectionTestUtils.setField(plan, "ownerId", ownerId);
        ReflectionTestUtils.setField(plan, "unitPrice", unitPrice);
        return plan;
    }

    @Test
    void 점주와_단가를_돌려준다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));

        PlanSnapshot snapshot = service.get(DAY, 7L);

        assertThat(snapshot.ownerId()).isEqualTo(3L);
        assertThat(snapshot.unitPrice()).isEqualTo(10L);
    }

    @Test
    void 같은_키의_두_번째_조회는_저장소를_부르지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));

        service.get(DAY, 7L);
        PlanSnapshot second = service.get(DAY, 7L);

        assertThat(second.ownerId()).isEqualTo(3L);
        verify(planRepository, times(1)).findByCampaignIdAndBusinessDate(7L, DAY);
    }

    @Test
    void 다른_영업일은_다시_조회한다() {
        LocalDate next = DAY.plusDays(1);
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));
        when(planRepository.findByCampaignIdAndBusinessDate(7L, next)).thenReturn(Optional.of(plan(3L, 20L)));

        assertThat(service.get(DAY, 7L).unitPrice()).isEqualTo(10L);
        assertThat(service.get(next, 7L).unitPrice()).isEqualTo(20L);
    }

    @Test
    void 계획_행이_없으면_예외이고_없는_결과는_캐시하지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY))
                .thenReturn(Optional.empty(), Optional.of(plan(3L, 10L)));

        assertThatThrownBy(() -> service.get(DAY, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");

        assertThat(service.get(DAY, 7L).ownerId()).isEqualTo(3L);
        verify(planRepository, times(2)).findByCampaignIdAndBusinessDate(7L, DAY);
    }
}
