package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.BudgetHourlyTarget;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.BudgetHourlyTargetRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class HourlyTargetPlannerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final BillingScheduleProperties SCHEDULE =
            new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(34, 33, 33));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private BudgetHourlyTargetRepository targetRepository;

    private HourlyTargetPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new HourlyTargetPlanner(planRepository, targetRepository, SCHEDULE, CLOCK);
    }

    private static BudgetDailyPlan plan(long planId, long reserved) {
        BudgetDailyPlan plan = BudgetDailyPlan.create(100L + planId, 7L, DAY, reserved, 10L, false, null);
        ReflectionTestUtils.setField(plan, "id", planId);
        return plan;
    }

    // 넣은 목표를 (계획 ID, 시, 금액) 순서대로 돌려준다
    private List<Tuple> inserted(int times) {
        ArgumentCaptor<Long> planIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> hours = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> amounts = ArgumentCaptor.forClass(Long.class);
        verify(targetRepository, times(times))
                .insertIfAbsent(planIds.capture(), hours.capture(), amounts.capture(), eq(NOW));
        verify(targetRepository, never()).saveAll(any());
        List<Tuple> result = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            result.add(tuple(planIds.getAllValues().get(i), hours.getAllValues().get(i), amounts.getAllValues().get(i)));
        }
        return result;
    }

    @Test
    void 중단되지_않은_계획이_없으면_아무것도_만들지_않는다() {
        when(planRepository.findByBusinessDateAndPausedFalse(DAY)).thenReturn(List.of());

        assertThat(planner.plan(DAY)).isZero();
        verifyNoInteractions(targetRepository);
    }

    @Test
    void 예약액을_가중치로_나누고_남는_금액은_마지막_시간대에_얹어_합을_예약액과_맞춘다() {
        when(planRepository.findByBusinessDateAndPausedFalse(DAY)).thenReturn(List.of(plan(3L, 10000L), plan(4L, 10L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(3L, 4L))).thenReturn(List.of());

        assertThat(planner.plan(DAY)).isEqualTo(2);

        // 조회 뒤 saveAll 이 아니라 이미 있으면 그대로 두는 INSERT 로 넣어 겹친 실행이 UNIQUE 위반으로 실패하지 않는다
        assertThat(inserted(6)).containsExactly(
                tuple(3L, 10, 3400L), tuple(3L, 11, 3300L), tuple(3L, 12, 3300L),
                tuple(4L, 10, 3L), tuple(4L, 11, 3L), tuple(4L, 12, 4L));
    }

    @Test
    void 목표가_이미_있는_계획은_건너뛴다() {
        when(planRepository.findByBusinessDateAndPausedFalse(DAY)).thenReturn(List.of(plan(3L, 10000L), plan(4L, 100L)));
        when(targetRepository.findByBudgetDailyPlanIdIn(List.of(3L, 4L)))
                .thenReturn(List.of(BudgetHourlyTarget.create(3L, 10, 3400L)));

        assertThat(planner.plan(DAY)).isEqualTo(1);
        assertThat(inserted(3)).extracting(t -> t.toList().get(0)).containsOnly(4L);
    }

    @Test
    void 목표는_한_트랜잭션에_넣는다() throws Exception {
        assertThat(HourlyTargetPlanner.class.getMethod("plan", LocalDate.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
