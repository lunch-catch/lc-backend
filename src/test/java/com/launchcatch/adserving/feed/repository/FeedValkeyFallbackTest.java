package com.launchcatch.adserving.feed.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Admission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("Valkey 가 멈췄을 때의 피드")
class FeedValkeyFallbackTest {

    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double bypassCount(String use) {
        return meterRegistry.counter(FeedRateLimitRepository.BYPASS_METRIC, "use", use).count();
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
    void 당일_이력을_읽지_못하면_빈_이력으로_통과시키고_센다() {
        SetOperations<String, String> setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(anyString())).thenThrow(new QueryTimeoutException("timeout"));

        var ids = new DailyExclusionRepository(redisTemplate, meterRegistry)
                .findCampaignIds(LocalDate.of(2026, 10, 1), 3001L);

        assertThat(ids).isEmpty();
        assertThat(bypassCount("daily_exclusion")).isEqualTo(1);
    }
}
