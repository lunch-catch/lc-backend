package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetPacingTest {

    // 24시간 균등 가중치다. 예약액 24,000 이면 시간대마다 1,000 이다
    private static final List<Integer> EVEN = Collections.nCopies(24, 1);

    private static long planned(LocalTime at) {
        return BudgetPacing.plannedAmountUntil(24_000L, EVEN, at);
    }

    @Test
    void 자정_정각은_0이다() {
        assertThat(planned(LocalTime.MIDNIGHT)).isZero();
    }

    @Test
    void 시간_중간은_그_시간대_목표를_경과_분으로_비례한다() {
        assertThat(planned(LocalTime.of(0, 30))).isEqualTo(500L);
        assertThat(planned(LocalTime.of(11, 30))).isEqualTo(11_500L);
    }

    @Test
    void 지난_시간대는_목표를_모두_더한다() {
        assertThat(planned(LocalTime.of(1, 0))).isEqualTo(1_000L);
        assertThat(planned(LocalTime.of(13, 0))).isEqualTo(13_000L);
    }

    @Test
    void 하루_끝_직전은_23시_목표의_59분_몫까지이고_고정하지_않는다() {
        assertThat(planned(LocalTime.of(23, 0))).isEqualTo(23_000L);
        assertThat(planned(LocalTime.of(23, 59))).isEqualTo(23_983L);
    }

    @Test
    void 예약액이_늘면_지난_시간대_목표도_새_예약액으로_나눈다() {
        assertThat(BudgetPacing.plannedAmountUntil(48_000L, EVEN, LocalTime.of(12, 0))).isEqualTo(24_000L);
    }

    @Test
    void 가중치로_나눈_몫은_내림하고_남는_금액은_마지막_가중치_시간대에_더한다() {
        assertThat(BudgetPacing.weightedTargets(10_000L, List.of(34, 33, 33))).containsExactly(3_400L, 3_300L, 3_300L);
        assertThat(BudgetPacing.weightedTargets(100L, List.of(34, 33, 33))).containsExactly(34L, 33L, 33L);
        assertThat(BudgetPacing.weightedTargets(10L, List.of(1, 1, 1))).containsExactly(3L, 3L, 4L);
        assertThat(BudgetPacing.weightedTargets(10L, List.of(1, 1, 0))).containsExactly(5L, 5L, 0L);
    }

    @Test
    void 가중치_합이_100이_아니어도_비율로_나눠_합이_예약액과_같다() {
        List<Long> targets = BudgetPacing.weightedTargets(10_000L, EVEN);

        assertThat(targets).hasSize(24);
        assertThat(targets.stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);
    }

    @Test
    void 분_단위_비례의_소수는_내림이다() {
        List<Integer> onlyMidnight = new ArrayList<>(Collections.nCopies(24, 0));
        onlyMidnight.set(0, 1);

        assertThat(BudgetPacing.plannedAmountUntil(100L, onlyMidnight, LocalTime.of(0, 1))).isEqualTo(1L);
        assertThat(BudgetPacing.plannedAmountUntil(100L, onlyMidnight, LocalTime.of(0, 59))).isEqualTo(98L);
    }

    @Test
    void 가중치가_0인_시간대는_목표가_0이다() {
        List<Integer> onlyTen = new ArrayList<>(Collections.nCopies(24, 0));
        onlyTen.set(10, 1);

        assertThat(BudgetPacing.plannedAmountUntil(5_000L, onlyTen, LocalTime.of(12, 0))).isEqualTo(5_000L);
        assertThat(BudgetPacing.plannedAmountUntil(5_000L, onlyTen, LocalTime.of(9, 0))).isZero();
    }

    @Test
    void 진행률은_소진을_계획으로_나누고_1을_넘어도_자르지_않는다() {
        assertThat(BudgetPacing.progressRate(6_060L, 5_050L)).isEqualTo(1.2);
        assertThat(BudgetPacing.progressRate(0L, 5_050L)).isZero();
    }

    @Test
    void 계획이_0이면_진행률은_0이다() {
        assertThat(BudgetPacing.progressRate(100L, 0L)).isZero();
    }
}
