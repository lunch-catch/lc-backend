package com.launchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpendFormulaTest {

    @Test
    void 소진액은_차감_합에서_양수_정정_합을_뺀_값이다() {
        assertThat(SpendFormula.spentAmount(1000, 200)).isEqualTo(800);
    }

    @Test
    void 정정이_없으면_차감_합_그대로다() {
        assertThat(SpendFormula.spentAmount(1000, 0)).isEqualTo(1000);
    }

    @Test
    void 정정이_차감보다_크면_0으로_자르지_않고_음수로_둔다() {
        assertThat(SpendFormula.spentAmount(100, 350)).isEqualTo(-250);
    }
}
