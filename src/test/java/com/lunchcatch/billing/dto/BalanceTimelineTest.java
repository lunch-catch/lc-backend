package com.lunchcatch.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BalanceTimelineTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 1, 0, 0, 5);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 13, 30);

    private final BalanceTimeline timeline = new BalanceTimeline(22000, List.of(
            new BalanceStep(10L, T0, -10000L),
            new BalanceStep(11L, T0, 500L),
            new BalanceStep(12L, T1, 200L)));

    @Test
    void 첫_줄보다_앞서면_시작_잔액이다() {
        assertThat(timeline.before(T0.minusSeconds(1), 99L)).isEqualTo(22000);
        assertThat(timeline.before(T0, 10L)).isEqualTo(22000);
    }

    @Test
    void 기록_시각이_같으면_ID가_작은_줄까지만_반영한다() {
        assertThat(timeline.before(T0, 11L)).isEqualTo(12000);
        assertThat(timeline.before(T0, 12L)).isEqualTo(12500);
    }

    @Test
    void 잔액을_바꾸지_않는_줄의_위치는_직전_줄까지의_누적을_얻는다() {
        assertThat(timeline.before(LocalDateTime.of(2026, 10, 1, 9, 0), 500L)).isEqualTo(12500);
    }

    @Test
    void 마지막_줄_뒤에서는_전체_누적이다() {
        assertThat(timeline.before(T1, 13L)).isEqualTo(12700);
    }

    @Test
    void 줄이_없으면_언제나_시작_잔액이다() {
        assertThat(new BalanceTimeline(5000, List.of()).before(T1, 1L)).isEqualTo(5000);
    }
}
