package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
        return plan(100L, 7L, ownerId, unitPrice);
    }

    private static BudgetDailyPlan plan(long planId, long campaignId, Long ownerId, long unitPrice) {
        BudgetDailyPlan plan = BeanUtils.instantiateClass(BudgetDailyPlan.class);
        ReflectionTestUtils.setField(plan, "id", planId);
        ReflectionTestUtils.setField(plan, "campaignId", campaignId);
        ReflectionTestUtils.setField(plan, "ownerId", ownerId);
        ReflectionTestUtils.setField(plan, "unitPrice", unitPrice);
        return plan;
    }

    @Test
    void 점주와_단가를_돌려준다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));

        PlanSnapshot snapshot = service.find(DAY, 7L).orElseThrow();

        assertThat(snapshot.ownerId()).isEqualTo(3L);
        assertThat(snapshot.unitPrice()).isEqualTo(10L);
    }

    @Test
    void 같은_키의_두_번째_조회는_저장소를_부르지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));

        service.find(DAY, 7L);
        PlanSnapshot second = service.find(DAY, 7L).orElseThrow();

        assertThat(second.ownerId()).isEqualTo(3L);
        verify(planRepository, times(1)).findByCampaignIdAndBusinessDate(7L, DAY);
    }

    @Test
    void 다른_영업일은_다시_조회한다() {
        LocalDate next = DAY.plusDays(1);
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan(3L, 10L)));
        when(planRepository.findByCampaignIdAndBusinessDate(7L, next)).thenReturn(Optional.of(plan(3L, 20L)));

        assertThat(service.find(DAY, 7L).orElseThrow().unitPrice()).isEqualTo(10L);
        assertThat(service.find(next, 7L).orElseThrow().unitPrice()).isEqualTo(20L);
    }

    @Test
    void 계획_행이_없으면_비어_있고_없는_결과는_캐시하지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY))
                .thenReturn(Optional.empty(), Optional.of(plan(3L, 10L)));

        assertThat(service.find(DAY, 7L)).isEmpty();

        assertThat(service.find(DAY, 7L).orElseThrow().ownerId()).isEqualTo(3L);
        verify(planRepository, times(2)).findByCampaignIdAndBusinessDate(7L, DAY);
    }

    @Test
    void 여러_캠페인은_캐시에_없는_것만_한_번의_쿼리로_읽고_요청_순서를_지킨다() {
        when(planRepository.findByCampaignIdAndBusinessDate(1L, DAY)).thenReturn(Optional.of(plan(101L, 1L, 3L, 10L)));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(2L, 3L)))
                .thenReturn(List.of(plan(103L, 3L, 4L, 30L), plan(102L, 2L, 3L, 20L)));
        service.find(DAY, 1L);

        Map<Long, PlanSnapshot> result = service.findAll(DAY, List.of(2L, 1L, 3L));

        assertThat(result.keySet()).containsExactly(2L, 1L, 3L);
        assertThat(result.get(1L).unitPrice()).isEqualTo(10L);
        assertThat(result.get(2L).unitPrice()).isEqualTo(20L);
        assertThat(result.get(3L).ownerId()).isEqualTo(4L);
        verify(planRepository, times(1)).findByBusinessDateAndCampaignIdIn(DAY, List.of(2L, 3L));
    }

    @Test
    void 여러_캠페인을_읽은_뒤에는_캐시에서만_답한다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(2L)))
                .thenReturn(List.of(plan(102L, 2L, 3L, 20L)));

        service.findAll(DAY, List.of(2L));
        Map<Long, PlanSnapshot> second = service.findAll(DAY, List.of(2L));

        assertThat(second.get(2L).unitPrice()).isEqualTo(20L);
        verify(planRepository, times(1)).findByBusinessDateAndCampaignIdIn(DAY, List.of(2L));
    }

    @Test
    void 계획이_없는_캠페인은_결과에서_빠지고_캐시하지_않는다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(9L))).thenReturn(List.of());

        assertThat(service.findAll(DAY, List.of(9L))).isEmpty();
        assertThat(service.findAll(DAY, List.of(9L))).isEmpty();
        verify(planRepository, times(2)).findByBusinessDateAndCampaignIdIn(DAY, List.of(9L));
    }
}
