package com.launchcatch.adserving.feed.repository;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Admission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("Valkey 가 멈췄을 때의 피드")
class FeedValkeyFallbackTest {

    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double bypassCount(String use) {
        return meterRegistry.counter(ExclusionRepository.BYPASS_METRIC, "use", use).count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 요청_제한을_세지_못하면_통과시키고_지금_바로_다시_받을_수_있다고_답하며_센다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        Admission admission = new FeedRateLimitRepository(redisTemplate, meterRegistry).tryAdmit(3001L, NOW);

        assertThat(admission).isEqualTo(new Admission(true, NOW));
        assertThat(bypassCount("feed_rate_limit")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 내려준_횟수를_올리지_못해도_예외를_올리지_않고_센다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        new ServeFrequencyRepository(redisTemplate, meterRegistry)
                .recordServed(LocalDate.of(2026, 10, 1), 3001L, java.util.List.of(101L), NOW);

        assertThat(bypassCount("serve_frequency")).isEqualTo(1);
    }

    // 하루와 세션 횟수, 당일 이력, 유효 노출 수를 비우고 필터와 30일 찜은 캐시 없음으로 두어 DB 에서 읽게 한다(#1042 결정 5)
    @Test
    @SuppressWarnings("unchecked")
    void 피드_상태를_읽지_못하면_다섯_모두_우회하고_쓰임새마다_센다() {
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        var state = new FeedStateRepository(redisTemplate, meterRegistry)
                .read(LocalDate.of(2026, 10, 1), 3001L, java.util.List.of(101L));

        assertThat(state.history().dailyCounts()).isEmpty();
        assertThat(state.history().sessionCampaignIds()).isEmpty();
        assertThat(state.excludedCampaignIds()).isEmpty();
        assertThat(state.validCounts()).isEmpty();
        assertThat(state.filter()).isNull();
        assertThat(state.wishes()).isNull();
        for (String use : java.util.List.of("serve_frequency", "exclusion", "impression_count", "feed_filter", "feed_wishes")) {
            assertThat(bypassCount(use)).isEqualTo(1);
        }
    }

    @Test
    void serve_기록을_쓰지_못해도_예외를_올리지_않고_센다() {
        when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.SessionCallback.class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        new ServeRecordRepository(redisTemplate, meterRegistry).saveAll(java.util.List.of(new ServeRecord(
                java.util.UUID.randomUUID(), 3001L, 101L, 55L, com.launchcatch.adserving.engine.SlotLabels.RELEVANCE, 1, NOW)));

        assertThat(bypassCount("serve_record")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serve_기록을_읽지_못하면_기록_없음으로_보고_센다() {
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        var record = new ServeRecordRepository(redisTemplate, meterRegistry).findById(java.util.UUID.randomUUID());

        assertThat(record).isEmpty();
        assertThat(bypassCount("serve_record")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 유효_노출_수를_올리지_못해도_예외를_올리지_않고_센다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        new ImpressionCountRepository(redisTemplate, meterRegistry).increment(LocalDate.of(2026, 10, 1), 101L);

        assertThat(bypassCount("impression_count")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 당일_이력에_넣지_못해도_예외를_올리지_않고_센다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("timeout"));

        new ExclusionRepository(redisTemplate, meterRegistry).add(LocalDate.of(2026, 10, 1), 3001L, 101L, NOW);

        assertThat(bypassCount("exclusion")).isEqualTo(1);
    }
}
