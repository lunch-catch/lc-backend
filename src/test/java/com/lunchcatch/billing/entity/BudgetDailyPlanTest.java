package com.lunchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class BudgetDailyPlanTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Test
    void 예약액이_0이어도_계획을_만들고_캠페인_상태를_옮긴다() {
        BudgetDailyPlan plan = BudgetDailyPlan.create(5L, 7L, DAY, 0L, 10L, true, CHANGED_AT);

        assertThat(plan.getCampaignId()).isEqualTo(5L);
        assertThat(plan.getOwnerId()).isEqualTo(7L);
        assertThat(plan.getBusinessDate()).isEqualTo(DAY);
        assertThat(plan.getDailyBudget()).isZero();
        assertThat(plan.getUnitPrice()).isEqualTo(10L);
        assertThat(plan.isPaused()).isTrue();
        assertThat(plan.getStatusChangedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void 식별자가_비거나_예약액이_음수거나_단가가_0_이하면_만들_수_없다() {
        assertThatThrownBy(() -> BudgetDailyPlan.create(null, 7L, DAY, 0L, 10L, false, CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetDailyPlan.create(5L, null, DAY, 0L, 10L, false, CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetDailyPlan.create(5L, 7L, null, 0L, 10L, false, CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetDailyPlan.create(5L, 7L, DAY, -1L, 10L, false, CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetDailyPlan.create(5L, 7L, DAY, 0L, 0L, false, CHANGED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 충전_직후_재예약이_예약액을_늘린다() {
        BudgetDailyPlan plan = BudgetDailyPlan.create(5L, 7L, DAY, 3000L, 10L, false, CHANGED_AT);

        plan.increaseDailyBudget(10000L);

        assertThat(plan.getDailyBudget()).isEqualTo(10000L);
    }

    @Test
    void 새_예약액이_지금_값보다_크지_않으면_거부한다() {
        BudgetDailyPlan plan = BudgetDailyPlan.create(5L, 7L, DAY, 3000L, 10L, false, CHANGED_AT);

        assertThatThrownBy(() -> plan.increaseDailyBudget(3000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan.increaseDailyBudget(2999L)).isInstanceOf(IllegalArgumentException.class);
    }
}
