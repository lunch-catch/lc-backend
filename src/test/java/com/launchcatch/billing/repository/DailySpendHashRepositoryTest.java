package com.launchcatch.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
}
