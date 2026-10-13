package com.launchcatch.adserving.feed.repository;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 피드 요청 제한(최근 60초 6회)을 Valkey 정렬 집합으로 센다.
 * 분 단위 고정 창으로 세면 경계에서 두 배까지 받고 Retry-After 가 들쭉날쭉해져 요청 시각을 그대로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class FeedRateLimitRepository {

    public static final Duration WINDOW = Duration.ofSeconds(60);
    public static final int LIMIT = 6;

    private static final String KEY_PREFIX = "adserving:feed-rate:";
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACQUIRE_SCRIPT = loadAcquireScript();

    private final StringRedisTemplate redisTemplate;

    /*
     * 한도 안이면 이 요청의 자리를 잡는다.
     * 잡지 못했거나 잡은 뒤 한도에 닿았으면 availableAtMillis 는 가장 오래된 요청이 창을 벗어나는 시각이다.
     */
    public Acquisition acquire(Long memberId, String requestId, long nowMillis) {
        List<?> result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key(memberId)),
                String.valueOf(nowMillis), String.valueOf(WINDOW.toMillis()), String.valueOf(LIMIT), requestId);
        return new Acquisition(((Number) result.get(0)).longValue() == 1L, ((Number) result.get(1)).longValue());
    }

    // 200 으로 끝나지 않은 요청의 자리를 돌려놓는다
    public void release(Long memberId, String requestId) {
        redisTemplate.opsForZSet().remove(key(memberId), requestId);
    }

    static String key(Long memberId) {
        return KEY_PREFIX + memberId;
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> loadAcquireScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/adserving_feed_rate_acquire.lua"));
        script.setResultType(List.class);
        return script;
    }

    public record Acquisition(boolean acquired, long availableAtMillis) {
    }
}
