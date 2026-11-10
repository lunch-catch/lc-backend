package com.lunchcatch.billing.payment.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.payment.exception.ChargeRateLimitedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class ChargeRateLimiterTest {

    private static final Long OWNER_ID = 7L;
    private static final String KEY = ChargeRateLimiter.KEY_PREFIX + OWNER_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    private SimpleMeterRegistry meterRegistry;
    private ChargeRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        rateLimiter = new ChargeRateLimiter(redisTemplate, meterRegistry);
    }

    @Test
    void 십초에_세번까지는_통과한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

        assertThatCode(() -> rateLimiter.checkLimit(OWNER_ID)).doesNotThrowAnyException();
        assertThat(count("rejected")).isZero();
    }

    @Test
    void 네번째부터는_카운터의_남은_수명을_Retry_After_로_싣고_거절한다() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("10000"))).thenReturn(4L);
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenReturn(6L);

        assertThatThrownBy(() -> rateLimiter.checkLimit(OWNER_ID))
                .isInstanceOfSatisfying(ChargeRateLimitedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.CHARGE_RATE_LIMITED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(6L);
                });
        assertThat(count("rejected")).isEqualTo(1);
    }

    @Test
    void 남은_수명을_읽지_못하면_창_길이로_답한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);
        when(redisTemplate.getExpire(KEY, TimeUnit.SECONDS)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> rateLimiter.checkLimit(OWNER_ID))
                .isInstanceOfSatisfying(ChargeRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(10L));
    }

    @Test
    void Valkey_가_멈추면_세지_못한_채_통과시키고_우회를_센다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimiter.checkLimit(OWNER_ID)).doesNotThrowAnyException();
        assertThat(count("bypassed")).isEqualTo(1);
    }

    private double count(String result) {
        return meterRegistry.get(ChargeRateLimiter.METRIC).tag("result", result).counter().count();
    }
}
