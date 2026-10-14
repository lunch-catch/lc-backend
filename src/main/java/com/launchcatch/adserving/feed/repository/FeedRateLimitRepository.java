package com.launchcatch.adserving.feed.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

// 사용자마다 최근 창 안의 피드 요청을 센다. 창은 미끄러진다.
@Repository
@RequiredArgsConstructor
public class FeedRateLimitRepository {

    private static final String KEY_PREFIX = "feedRateLimit:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_rate_limit.lua"));
        script.setResultType(List.class);
        return script;
    }

    // 통과하면 이번 요청을 넣고 센다. 거부되면 넣지 않는다.
    public RateLimitResult tryAcquire(Long memberId, Instant now, int limit, Duration window) {
        List<?> result = redisTemplate.execute(SCRIPT, List.of(KEY_PREFIX + memberId),
                String.valueOf(now.toEpochMilli()), String.valueOf(window.toMillis()),
                String.valueOf(limit), UUID.randomUUID().toString());
        return new RateLimitResult(
                ((Number) result.get(0)).longValue() == 1,
                ((Number) result.get(1)).intValue(),
                Instant.ofEpochMilli(((Number) result.get(2)).longValue()));
    }
}
