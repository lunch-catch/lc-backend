package com.lunchcatch.adserving.feed.repository;

import static com.lunchcatch.adserving.exclusion.repository.ExclusionRepository.BYPASS_METRIC;

import com.lunchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 사용자마다 최근 60초 동안 6회까지 피드를 받게 한다(FR-064).
 * 분 단위 고정 창으로 세면 경계에서 두 배까지 받고 Retry-After 가 들쭉날쭉해져 요청 시각을 그대로 둔다.
 * 거부된 요청은 넣지 않는다. 넣으면 알려 준 시각에 다시 와도 또 거부될 수 있다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FeedRateLimitRepository {

    static final String KEY_PREFIX = "adserving:feedRequests:";
    static final Duration WINDOW = Duration.ofSeconds(60);
    static final int LIMIT = 6;

    private static final RedisScript<List> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_rate_limit.lua"));
        script.setResultType(List.class);
        return script;
    }

    /*
     * 이 요청을 넣을 수 있으면 넣고 다음 요청이 들어올 수 있는 시각과 함께 돌려준다.
     * Valkey 가 멈추면 통과시킨다. 캐시는 성능 장치라 이 제한 하나로 피드 전체를 닫지 않는다(장애 처리 가이드의 캐시 기본 처리).
     */
    public Admission tryAdmit(Long memberId, Instant now) {
        try {
            List<?> result = redisTemplate.execute(SCRIPT, List.of(KEY_PREFIX + memberId),
                    String.valueOf(now.toEpochMilli()), String.valueOf(WINDOW.toMillis()),
                    String.valueOf(LIMIT), now.toEpochMilli() + ":" + UUID.randomUUID());
            boolean admitted = ((Number) result.get(0)).longValue() == 1;
            long nextAvailableMs = ((Number) result.get(1)).longValue();
            Instant nextAvailableAt = nextAvailableMs < 0 ? now : Instant.ofEpochMilli(nextAvailableMs);
            return new Admission(admitted, nextAvailableAt);
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_RATE_LIMIT_FAILED memberId={} reason={} fallback=ADMIT",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "feed_rate_limit").increment();
            return new Admission(true, now);
        }
    }

    // admitted 가 거짓이면 이 요청은 세지 않았다. nextAvailableAt 은 다음 요청이 거부되지 않는 가장 이른 시각이다.
    public record Admission(boolean admitted, Instant nextAvailableAt) {
    }
}
