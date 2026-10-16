package com.launchcatch.billing.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.contract.DeductionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class ValkeyDailySpendCounterTest {

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

        boolean applied = new ValkeyDailySpendCounter(redisTemplate).applyStatus(DAY, 12L, true, changedAt);

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
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 10, 0);

        assertThat(repository.applyStatus(DAY, 12L, false, changedAt)).isFalse();
        assertThat(repository.applyStatus(DAY, 12L, false, changedAt)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 재예약_반영은_새_예약액을_넘기고_올렸는지를_돌려준다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 0L, (Long) null);
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);

        boolean increased = repository.increaseReserved(DAY, 12L, 10000L);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(increased).isTrue();
        assertThat(keys.getValue()).containsExactly("billing:day:2026-10-01:12");
        assertThat(args.getValue()).containsExactly("10000");
        assertThat(repository.increaseReserved(DAY, 12L, 10000L)).isFalse();
        assertThat(repository.increaseReserved(DAY, 12L, 10000L)).isFalse();
    }

    @Test
    void 해시를_지울_때도_같은_키를_쓴다() {
        new ValkeyDailySpendCounter(redisTemplate).evict(DAY, 12L);

        verify(redisTemplate).delete("billing:day:2026-10-01:12");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 판정은_해시와_처리_표시_두_키에_단가와_표시_수명을_넘긴다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("BILLED");

        DeductOutcome outcome = new ValkeyDailySpendCounter(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L);

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
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);

        assertThat(repository.deduct(DAY, 12L, SERVE_ID, 10L).status()).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        assertThat(repository.deduct(DAY, 12L, SERVE_ID, 10L).status()).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
    }

    @Test
    void 이미_처리된_노출이라는_응답은_처음_판정을_담은_재호출_결과다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("REPLAY:BILLED", "BILLED");
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);

        DeductOutcome replayed = repository.deduct(DAY, 12L, SERVE_ID, 10L);
        DeductOutcome fresh = repository.deduct(DAY, 12L, SERVE_ID, 10L);

        assertThat(replayed.status()).isEqualTo(DeductionStatus.BILLED);
        assertThat(replayed.replayed()).isTrue();
        assertThat(fresh.replayed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 되돌리기는_해시와_처리_표시_두_키에_단가를_넘기고_되돌렸는지를_돌려준다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 0L, (Long) null);
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);

        assertThat(repository.undo(DAY, 12L, SERVE_ID, 10L)).isTrue();
        assertThat(repository.undo(DAY, 12L, SERVE_ID, 10L)).isFalse();
        assertThat(repository.undo(DAY, 12L, SERVE_ID, 10L)).isFalse();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, org.mockito.Mockito.times(3)).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getAllValues().get(0)).containsExactly(
                "billing:day:2026-10-01:12", "billing:served:0192f3a4-7b5c-7d1e-9a2b-3c4d5e6f7a8b");
        assertThat(args.getAllValues().get(0)).containsExactly("10");
    }

    @Test
    void 해시가_없다는_응답은_판정이_아니라_해시_없음이다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("HASH_MISSING");

        DeductOutcome outcome = new ValkeyDailySpendCounter(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L);

        assertThat(outcome.isHashMissing()).isTrue();
        assertThat(outcome.status()).isNull();
    }

    @Test
    void 응답이_없으면_대체_판정을_만들지_않고_예외다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThatThrownBy(() -> new ValkeyDailySpendCounter(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 모르는_응답은_예외다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("???");

        assertThatThrownBy(() -> new ValkeyDailySpendCounter(redisTemplate).deduct(DAY, 12L, SERVE_ID, 10L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 재구성은_네_필드와_사흘_수명을_넘기고_만들었으면_true다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 1, 0, 0, 0, 1_000);

        boolean created = new ValkeyDailySpendCounter(redisTemplate).rebuildIfAbsent(DAY, 12L, 1000L, 250L, true, changedAt);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(created).isTrue();
        assertThat(keys.getValue()).containsExactly("billing:day:2026-10-01:12");
        assertThat(args.getValue()).containsExactly("1000", "250", "1", "1790780400000001", "259200");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 변경_시각이_없으면_0으로_적는다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        new ValkeyDailySpendCounter(redisTemplate).rebuildIfAbsent(DAY, 12L, 1000L, 0L, false, null);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), args.capture());
        assertThat(args.getValue()).containsExactly("1000", "0", "0", "0", "259200");
    }

    @Test
    void 이미_있거나_응답이_없으면_false다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L, (Long) null);
        ValkeyDailySpendCounter repository = new ValkeyDailySpendCounter(redisTemplate);

        assertThat(repository.rebuildIfAbsent(DAY, 12L, 1000L, 0L, false, null)).isFalse();
        assertThat(repository.rebuildIfAbsent(DAY, 12L, 1000L, 0L, false, null)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private HashOperations<String, String, String> stubPipeline(List<Object> replies) {
        RedisOperations<String, String> operations = mock(RedisOperations.class);
        HashOperations<String, String, String> hash = mock(HashOperations.class);
        when(operations.<String, String>opsForHash()).thenReturn(hash);
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenAnswer(invocation -> {
            SessionCallback<?> callback = invocation.getArgument(0);
            callback.execute(operations);
            return replies;
        });
        return hash;
    }

    @Test
    void 다건_조회는_캠페인마다_한_키씩_파이프라인으로_읽고_없는_해시는_뺀다() {
        HashOperations<String, String, String> hash = stubPipeline(List.of(
                List.of("1000", "250"),
                Arrays.asList(null, null),
                Arrays.asList("500", null)));

        Map<Long, DailySpend> result = new ValkeyDailySpendCounter(redisTemplate).readAll(DAY, List.of(1L, 2L, 3L));

        assertThat(result).containsOnlyKeys(1L, 3L);
        assertThat(result.get(1L)).isEqualTo(new DailySpend(1000L, 250L));
        assertThat(result.get(3L)).isEqualTo(new DailySpend(500L, 0L));
        verify(hash).multiGet("billing:day:2026-10-01:1", List.of("reserved", "spend"));
        verify(hash).multiGet("billing:day:2026-10-01:2", List.of("reserved", "spend"));
        verify(hash).multiGet("billing:day:2026-10-01:3", List.of("reserved", "spend"));
    }

    @Test
    void 캠페인이_없으면_Valkey를_부르지_않는다() {
        Map<Long, DailySpend> result = new ValkeyDailySpendCounter(redisTemplate).readAll(DAY, List.of());

        assertThat(result).isEmpty();
        verify(redisTemplate, never()).executePipelined(any(SessionCallback.class));
    }
}
