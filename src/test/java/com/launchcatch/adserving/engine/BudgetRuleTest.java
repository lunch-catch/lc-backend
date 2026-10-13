package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.contract.BudgetProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("예산으로 후보를 빼는 규칙")
class BudgetRuleTest {

    @ParameterizedTest(name = "진행률 {0}, 유효 노출 {1} 이면 뺀다 = {2}")
    @CsvSource({
            "1.2,       0,    false",
            "1.2000001, 0,    true",
            "0.5,       1049, false",
            "0.5,       1050, true"
    })
    void 진행률이_1_2_를_넘거나_유효_노출이_상한에_닿으면_뺀다(double progressRate, long validImpressions, boolean excluded) {
        BudgetProgress progress = new BudgetProgress(101L, 10_000, 10, 0, 0, progressRate);

        assertThat(BudgetRule.excludes(progress, validImpressions)).isEqualTo(excluded);
    }

    @ParameterizedTest(name = "예약액 {0}, 단가 {1} 이면 상한 {2}")
    @CsvSource({
            "10000, 10, 1050",
            "999,   10, 104"
    })
    void 노출_상한은_예약액을_단가로_나눈_값의_1_05_배를_내림한다(long reserved, long unitPrice, long cap) {
        BudgetProgress progress = new BudgetProgress(101L, reserved, unitPrice, 0, 0, 0);

        assertThat(BudgetRule.impressionCap(progress)).isEqualTo(cap);
    }
}
