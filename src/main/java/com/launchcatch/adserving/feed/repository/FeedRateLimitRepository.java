package com.launchcatch.adserving.feed.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 사용자마다 최근 60초 안의 피드 요청 시각을 Valkey 정렬 집합에 둔다.
 * 분 단위 고정 창으로 세면 경계에서 두 배까지 받고 Retry-After 가 들쭉날쭉해진다(feed-api-design.md 요청 제한 구현).
 */
@Repository
@RequiredArgsConstructor
public class FeedRateLimitRepository {

    public static final int LIMIT = 6;
    public static final Duration WINDOW = Duration.ofSeconds(60);

    private static final String KEY_PREFIX = "adserving:feedRate:";
    private static final RedisScript<List> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;

    /*
     * 받은 요청이면 entry 로 기록하고, 한도를 넘으면 기록하지 않는다.
     * nextAvailableAt 은 다음 요청이 거부되지 않는 가장 이른 시각이다.
     */
    public record Decision(boolean accepted, Instant nextAvailableAt) {
    }

    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_rate_limit.lua"));
        script.setResultType(List.class);
        return script;
    }

    public Decision acquire(Long memberId, String entry, Instant now) {
        List<?> result = redisTemplate.execute(SCRIPT, List.of(key(memberId)),
                String.valueOf(now.toEpochMilli()), String.valueOf(WINDOW.toMillis()), String.valueOf(LIMIT), entry);
        boolean accepted = ((Number) result.get(0)).longValue() == 1L;
        long count = ((Number) result.get(1)).longValue();
        Instant oldest = Instant.ofEpochMilli(((Number) result.get(2)).longValue());
        Instant nextAvailableAt = count < LIMIT ? now : oldest.plus(WINDOW);
        return new Decision(accepted, nextAvailableAt);
    }

    // 200 으로 끝나지 않은 요청의 기록을 지운다. 거부되거나 실패한 요청은 횟수에 넣지 않는다
    public void release(Long memberId, String entry) {
        redisTemplate.opsForZSet().remove(key(memberId), entry);
    }

    private static String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }
}
