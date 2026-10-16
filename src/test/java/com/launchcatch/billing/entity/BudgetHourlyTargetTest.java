package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BudgetHourlyTargetTest {

    @Test
    void 계획과_시와_목표_금액을_담는다() {
        BudgetHourlyTarget target = BudgetHourlyTarget.create(3L, 12, 3300L);

        assertThat(target.getBudgetDailyPlanId()).isEqualTo(3L);
        assertThat(target.getHour()).isEqualTo((byte) 12);
        assertThat(target.getTargetAmount()).isEqualTo(3300L);
    }

    @Test
    void 계획_ID가_없거나_시가_범위를_벗어나거나_목표가_음수면_만들_수_없다() {
        assertThatThrownBy(() -> BudgetHourlyTarget.create(null, 10, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetHourlyTarget.create(3L, 24, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetHourlyTarget.create(3L, -1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetHourlyTarget.create(3L, 10, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
