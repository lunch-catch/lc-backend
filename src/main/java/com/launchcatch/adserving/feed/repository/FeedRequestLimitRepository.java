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

/*
 * 사용자마다 최근 60초 동안 6회까지 피드를 받게 한다(67행).
 *
 * 분 단위 고정 창으로 세면 경계에서 두 배까지 받거나 Retry-After 가 들쭉날쭉해진다(feed-api-design.md).
 * 그래서 창 안의 요청 시각을 정렬 집합에 두고 Lua 한 번으로 지우기, 세기, 넣기를 함께 한다.
 * 시각은 Valkey 의 TIME 이 아니라 애플리케이션 시계로 넣는다. 응답의 nextFeedAvailableAt 과 같은 시계라야 둘이 맞는다.
 */
@Repository
@RequiredArgsConstructor
public class FeedRequestLimitRepository {

    static final int LIMIT = 6;
    static final Duration WINDOW = Duration.ofSeconds(60);

    private static final String KEY_PREFIX = "adserving:feed:requests:";
    private static final RedisScript<List> RATE_LIMIT_SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    public FeedRequestPermit tryAcquire(Long memberId, Instant now) {
        String requestId = UUID.randomUUID().toString();
        List<Long> result = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(KEY_PREFIX + memberId),
                String.valueOf(now.toEpochMilli()), String.valueOf(WINDOW.toMillis()), String.valueOf(LIMIT),
                requestId);
        boolean allowed = result.get(0) == 1L;
        long oldest = result.get(1);
        Instant nextAvailableAt = oldest < 0 ? now : Instant.ofEpochMilli(oldest).plus(WINDOW);
        return new FeedRequestPermit(allowed, nextAvailableAt, requestId);
    }

    // 200 으로 끝나지 않은 요청을 창에서 뺀다(feed-api-spec.md "200 으로 끝난 요청은 횟수에 들어간다")
    public void release(Long memberId, String requestId) {
        redisTemplate.opsForZSet().remove(KEY_PREFIX + memberId, requestId);
    }

    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/adserving_feed_rate_limit.lua"));
        script.setResultType(List.class);
        return script;
    }
}
