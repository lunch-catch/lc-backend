package com.launchcatch.auth.jwt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class AccessTokenValidAfterRepositoryTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AccessTokenValidAfterRepository repository = new AccessTokenValidAfterRepository(redis);

    @Test
    void 원자적_차단명령의_응답이_없으면_저장실패다() {
        assertThatThrownBy(() -> repository.invalidateBefore(Role.ADMIN, 1L, LocalDateTime.now(), Duration.ofMinutes(30)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 원자적_차단기준을_고정_정밀도와_TTL로_저장한다() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        repository.invalidateBefore(Role.ADMIN, 1L, LocalDateTime.of(2026, 10, 9, 12, 0), Duration.ofMinutes(30));
        verify(redis).execute(any(RedisScript.class), eq(java.util.List.of("accessTokenValidAfter:ADMIN:1")),
                eq("2026-10-09T12:00:00.000000000"), eq("1800000"));
    }

    @Test
    void 폐기기준_이전은_차단하고_동일하거나_이후는_허용한다() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("accessTokenValidAfter:ADMIN:1")).thenReturn("2026-10-09T12:00:00.123000000");
        var time = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 123000000);
        assertThat(repository.isValidAfter(Role.ADMIN, 1L, time.minusNanos(1))).isFalse();
        assertThat(repository.isValidAfter(Role.ADMIN, 1L, time)).isTrue();
        assertThat(repository.isValidAfter(Role.ADMIN, 1L, time.plusNanos(1))).isTrue();
    }
}
