package com.launchcatch.owner.service;

import com.launchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class OwnerPlaceSearchRateLimiter {
    private static final String KEY_PREFIX = "kakaoPlaceSearchRateLimit:";
    private static final int LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public void checkLimit(Long ownerId) {
        String key = KEY_PREFIX + ownerId;
        Long count;
        try {
            count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
        } catch (DataAccessException e) {
            String cause = RedisFailureClassifier.causeLabel(e);
            meterRegistry.counter("owner.place.search.rate.limit.bypassed", "cause", cause).increment();
            log.warn("event=KAKAO_PLACE_SEARCH_RATE_LIMIT_CHECK_FAILED ownerId={} cause={} 통과시킨다",
                    ownerId, cause, e);
            return;
        }
        if (count != null && count > LIMIT) {
            // TODO: 카카오 로컬 검색 제한을 초과한 경우의 오류 코드를 정의하고 도메인 예외로 바꿔야 한다.
            throw new IllegalStateException("가게 검색 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
