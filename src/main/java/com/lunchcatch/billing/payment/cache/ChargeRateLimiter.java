package com.lunchcatch.billing.payment.cache;

import com.lunchcatch.auth.RedisFailureClassifier;
import com.lunchcatch.billing.payment.exception.ChargeRateLimitedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/*
 * 점주 1명의 충전 주문과 승인 확정을 합쳐 10초에 3회로 제한한다(#1122).
 *
 * 충전 승인은 점주 계정 행을 잠가 같은 점주의 요청을 한 줄로 세운다. 그 줄에 선 트랜잭션은 각자 공용 커넥션을 쥐고 기다리므로,
 * 한 점주에게 요청이 몰리면 관계없는 점주의 요청까지 커넥션을 받지 못한다. 그래서 몰린 요청을 커넥션을 받기 전에 돌려보낸다.
 * 정상 충전은 주문 1회와 승인 확정 1회이고 그 사이에 결제창이 있어, 승인 확정을 한두 번 다시 불러도 10초에 3회를 넘지 않는다.
 * 고정 창이라 창 경계에서 최대 6회가 몰릴 수 있다. 서버당 커넥션 풀 8개보다 작다.
 *
 * AuthRateLimitFilter 와 같은 고정 창 카운터(auth_rate_limit.lua)를 쓴다.
 * Valkey 장애 때는 세지 못하는 것뿐이지 충전을 막을 이유가 없어 통과시킨다(장애_처리_설계_가이드.md, 캐시 기본 처리).
 */
@Slf4j
@Component
public class ChargeRateLimiter {

    static final String METRIC = "billing.charge.rate.limit";
    static final String KEY_PREFIX = "billing:chargeRateLimit:";
    static final int LIMIT = 3;
    static final Duration WINDOW = Duration.ofSeconds(10);

    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public ChargeRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        // 첫 사건 전에도 시리즈가 있어야 increase() 가 첫 증가를 센다
        meterRegistry.counter(METRIC, "result", "rejected");
        meterRegistry.counter(METRIC, "result", "bypassed");
    }

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    // 상한을 넘으면 ChargeRateLimitedException 을 던진다. 넘지 않았거나 세지 못했으면 그대로 돌아간다
    public void checkLimit(Long ownerId) {
        String key = KEY_PREFIX + ownerId;
        Long count;
        try {
            count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
        } catch (DataAccessException e) {
            log.warn("event=BILLING_CHARGE_RATE_LIMIT_CHECK_FAILED ownerId={} reason={} fallback=PASS",
                    ownerId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(METRIC, "result", "bypassed").increment();
            return;
        }
        if (count != null && count > LIMIT) {
            meterRegistry.counter(METRIC, "result", "rejected").increment();
            throw new ChargeRateLimitedException(retryAfterSeconds(key, ownerId));
        }
    }

    /*
     * 고정 창이라 카운터 키의 남은 수명이 다시 요청해도 되는 때까지 남은 초다.
     * 수명을 읽지 못하면 창 길이로 답한다. 헤더를 빼면 클라이언트가 곧바로 다시 눌러 막으려던 요청이 다시 몰린다.
     */
    private long retryAfterSeconds(String key, Long ownerId) {
        try {
            Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return remaining != null && remaining > 0 ? remaining : WINDOW.toSeconds();
        } catch (DataAccessException e) {
            log.warn("event=BILLING_CHARGE_RATE_LIMIT_TTL_FAILED ownerId={} reason={} fallback=WINDOW_LENGTH",
                    ownerId, RedisFailureClassifier.causeLabel(e), e);
            return WINDOW.toSeconds();
        }
    }
}
