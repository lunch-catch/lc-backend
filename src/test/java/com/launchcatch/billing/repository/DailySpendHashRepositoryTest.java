package com.launchcatch.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.contract.DeductionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class DailySpendHashRepositoryTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final UUID SERVE_ID = UUID.fromString("0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void 키는_영업일과_캠페인이고_중단_여부와_마이크로초_시각을_넘긴다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        // 2026-10-01 00:00:00.000001 KST 는 2026-09-30 15:00:00.000001 UTC 다
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 0, 0, 0, 1_000);

        boolean applied = new DailySpendHashRepository(redisTemplate).applyStatus(DAY, 12L, true, changedAt);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(applied).isTrue();
        assertThat(keys.getValue()).containsExactly("billing:day:2026-10-01:12");
        assertThat(args.getValue()).containsExactly("1", "1790780400000001");
    }

    @Test
    void 스크립트가_반영하지_않았거나_응답이_없으면_false다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L, (Long) null);
        DailySpendHashRepository repository = new DailySpendHashRepository(redisTemplate);
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        assertThat(repository.applyStatus(DAY, 12L, false, changedAt)).isFalse();
        assertThat(repository.applyStatus(DAY, 12L, false, changedAt)).isFalse();
    }

    @Test
    void 해시를_지울_때도_같은_키를_쓴다() {
        new DailySpendHashRepository(redisTemplate).evict(DAY, 12L);

        verify(redisTemplate).delete("billing:day:2026-10-01:12");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 판정은_해시와_처리_표시_두_키에_단가와_표시_수명을_넘긴다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("BILLED");

        DeductOutcome outcome = new DailySpendHashRepository(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(outcome.status()).isEqualTo(DeductionStatus.BILLED);
        assertThat(outcome.isHashMissing()).isFalse();
        assertThat(keys.getValue()).containsExactly(
                "billing:day:2026-10-01:12", "billing:served:0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");
        assertThat(args.getValue()).containsExactly("10", "172800");
    }

    @Test
    void 세_가지_판정을_그대로_돌려준다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("NOT_BILLED_BUDGET", "NOT_BILLED_PAUSED");
        DailySpendHashRepository repository = new DailySpendHashRepository(redisTemplate);

        assertThat(repository.deduct(DAY, 12L, SERVE_ID, 10L).status()).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        assertThat(repository.deduct(DAY, 12L, SERVE_ID, 10L).status()).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
    }

    @Test
    void 해시가_없다는_응답은_판정이_아니라_해시_없음이다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("HASH_MISSING");

        DeductOutcome outcome = new DailySpendHashRepository(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L);

        assertThat(outcome.isHashMissing()).isTrue();
        assertThat(outcome.status()).isNull();
    }

    @Test
    void 응답이_없으면_대체_판정을_만들지_않고_예외다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThatThrownBy(() -> new DailySpendHashRepository(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 모르는_응답은_예외다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("???");

        assertThatThrownBy(() -> new DailySpendHashRepository(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
