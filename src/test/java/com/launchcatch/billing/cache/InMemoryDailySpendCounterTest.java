package com.launchcatch.billing.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.contract.DeductionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 인메모리 Fake 가 Lua 스크립트와 같은 규칙으로 판정하는지 본다. 다른 시험이 이 Fake 를 믿고 쓴다
class InMemoryDailySpendCounterTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 14, 10, 5);

    private final InMemoryDailySpendCounter counter = new InMemoryDailySpendCounter();

    private DeductionStatus deduct(UUID serveId) {
        return counter.deduct(DAY, 1L, serveId, 10L).status();
    }

    @Test
    void 단가_10_예약_100이면_열_건은_과금하고_열한_번째는_예약액에_닿는다() {
        counter.seed(DAY, 1L, 100L, 0L, false);

        for (int i = 0; i < 10; i++) {
            assertThat(deduct(UUID.randomUUID())).isEqualTo(DeductionStatus.BILLED);
        }

        assertThat(deduct(UUID.randomUUID())).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        assertThat(counter.spendOf(DAY, 1L)).isEqualTo(100L);
    }

    @Test
    void 같은_serveId는_처음_판정을_돌려주고_소진을_늘리지_않는다() {
        counter.seed(DAY, 1L, 100L, 0L, false);
        UUID serveId = UUID.randomUUID();

        DeductionStatus first = deduct(serveId);
        DeductionStatus second = deduct(serveId);

        assertThat(second).isEqualTo(first).isEqualTo(DeductionStatus.BILLED);
        assertThat(counter.spendOf(DAY, 1L)).isEqualTo(10L);
    }

    @Test
    void 같은_serveId_재호출은_재호출_결과로_표시된다() {
        counter.seed(DAY, 1L, 100L, 0L, false);
        UUID serveId = UUID.randomUUID();

        assertThat(counter.deduct(DAY, 1L, serveId, 10L).replayed()).isFalse();
        assertThat(counter.deduct(DAY, 1L, serveId, 10L).replayed()).isTrue();
    }

    @Test
    void 되돌리면_처리_표시가_지워지고_BILLED였던_소진이_내려가며_두_번째는_아무것도_하지_않는다() {
        counter.seed(DAY, 1L, 100L, 0L, false);
        UUID serveId = UUID.randomUUID();
        deduct(serveId);

        assertThat(counter.undo(DAY, 1L, serveId, 10L)).isTrue();
        assertThat(counter.spendOf(DAY, 1L)).isZero();
        assertThat(counter.undo(DAY, 1L, serveId, 10L)).isFalse();
        assertThat(counter.spendOf(DAY, 1L)).isZero();
        assertThat(deduct(serveId)).isEqualTo(DeductionStatus.BILLED);
    }

    @Test
    void BILLED가_아닌_판정을_되돌려도_소진은_그대로이고_표시만_지워진다() {
        counter.seed(DAY, 1L, 100L, 0L, true);
        UUID serveId = UUID.randomUUID();
        deduct(serveId);

        assertThat(counter.undo(DAY, 1L, serveId, 10L)).isTrue();
        assertThat(counter.spendOf(DAY, 1L)).isZero();
    }

    @Test
    void 해시가_사라진_뒤_되돌려도_예외_없이_표시만_지운다() {
        counter.seed(DAY, 1L, 100L, 0L, false);
        UUID serveId = UUID.randomUUID();
        deduct(serveId);
        counter.evict(DAY, 1L);

        assertThat(counter.undo(DAY, 1L, serveId, 10L)).isTrue();
        assertThat(counter.exists(DAY, 1L)).isFalse();
    }

    @Test
    void 중단된_캠페인은_예산이_남아도_과금하지_않는다() {
        counter.seed(DAY, 1L, 100L, 0L, true);

        assertThat(deduct(UUID.randomUUID())).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
        assertThat(counter.spendOf(DAY, 1L)).isZero();
    }

    @Test
    void 해시가_없으면_아무것도_쓰지_않고_처리_표시도_남기지_않는다() {
        UUID serveId = UUID.randomUUID();

        assertThat(counter.deduct(DAY, 1L, serveId, 10L).isHashMissing()).isTrue();

        counter.seed(DAY, 1L, 100L, 0L, false);
        assertThat(deduct(serveId)).isEqualTo(DeductionStatus.BILLED);
    }

    @Test
    void 재구성은_해시가_없을_때만_만들고_있으면_덮어쓰지_않는다() {
        assertThat(counter.rebuildIfAbsent(DAY, 1L, 100L, 30L, false, null)).isTrue();
        assertThat(counter.rebuildIfAbsent(DAY, 1L, 999L, 0L, true, T1)).isFalse();

        assertThat(counter.readAll(DAY, List.of(1L)).get(1L)).isEqualTo(new DailySpend(100L, 30L));
    }

    @Test
    void 중단_표시는_저장된_시각보다_늦은_변경만_반영한다() {
        counter.rebuildIfAbsent(DAY, 1L, 100L, 0L, false, T1);

        assertThat(counter.applyStatus(DAY, 1L, true, T2)).isTrue();
        assertThat(counter.applyStatus(DAY, 1L, false, T2)).isFalse();
        assertThat(counter.applyStatus(DAY, 1L, false, T1)).isFalse();
        assertThat(deduct(UUID.randomUUID())).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
    }

    @Test
    void 해시가_없으면_중단_표시를_반영하지_않는다() {
        assertThat(counter.applyStatus(DAY, 1L, true, T1)).isFalse();
        assertThat(counter.exists(DAY, 1L)).isFalse();
    }

    @Test
    void 지우면_다음_판정이_해시_없음이고_다건_조회는_있는_캠페인만_돌려준다() {
        counter.seed(DAY, 1L, 100L, 0L, false);
        counter.seed(DAY, 2L, 50L, 10L, false);
        counter.evict(DAY, 1L);

        assertThat(counter.deduct(DAY, 1L, UUID.randomUUID(), 10L).isHashMissing()).isTrue();
        assertThat(counter.readAll(DAY, List.of(1L, 2L))).containsOnlyKeys(2L);
    }
}
