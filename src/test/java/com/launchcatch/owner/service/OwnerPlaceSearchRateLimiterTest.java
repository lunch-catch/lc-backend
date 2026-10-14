package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class OwnerPlaceSearchRateLimiterTest {
    private static final String KEY = "kakaoPlaceSearchRateLimit:7";

    @Mock
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry metrics;
    private OwnerPlaceSearchRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        rateLimiter = new OwnerPlaceSearchRateLimiter(redisTemplate, metrics);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 9, 10})
    void 분당_10회까지_검색을_허용한다(long count) {
        when(redisTemplate.execute(any(), eq(List.of(KEY)), eq("60000"))).thenReturn(count);

        assertThatCode(() -> rateLimiter.checkLimit(7L)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {11, 100})
    void 분당_10회를_초과하면_명시적인_예외로_거절한다(long count) {
        when(redisTemplate.execute(any(), eq(List.of(KEY)), eq("60000"))).thenReturn(count);

        assertThatThrownBy(() -> rateLimiter.checkLimit(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("가게 검색 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void 점주마다_별도_카운터로_제한한다() {
        when(redisTemplate.execute(any(), eq(List.of(KEY)), eq("60000"))).thenReturn(11L);
        when(redisTemplate.execute(any(), eq(List.of("kakaoPlaceSearchRateLimit:8")), eq("60000"))).thenReturn(1L);

        assertThatThrownBy(() -> rateLimiter.checkLimit(7L)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> rateLimiter.checkLimit(8L)).doesNotThrowAnyException();
    }

    @Test
    void Redis_장애면_통과시키고_횟수를_센다() {
        when(redisTemplate.execute(any(), anyList(), eq("60000")))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimiter.checkLimit(7L)).doesNotThrowAnyException();
        assertThat(metrics.get("owner.place.search.rate.limit.bypassed").tag("cause", "OTHER").counter().count())
                .isEqualTo(1);
    }

    @Test
    void Redis_타임아웃이면_통과시키고_원인을_구별해_센다() {
        when(redisTemplate.execute(any(), anyList(), eq("60000"))).thenThrow(new QueryTimeoutException("timeout"));

        assertThatCode(() -> rateLimiter.checkLimit(7L)).doesNotThrowAnyException();
        assertThat(metrics.get("owner.place.search.rate.limit.bypassed").tag("cause", "TIMEOUT").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 카운터_결과가_없으면_통과시킨다() {
        assertThatCode(() -> rateLimiter.checkLimit(7L)).doesNotThrowAnyException();
    }
}
