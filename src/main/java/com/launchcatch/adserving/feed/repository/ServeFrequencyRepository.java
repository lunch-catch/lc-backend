package com.launchcatch.adserving.feed.repository;

import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 같은 캠페인을 한 사용자에게 하루 2회, 한 세션 1회까지만 내려주려고 내려준 횟수를 센다(비즈니스 규칙).
 * 세션은 30분 동안 피드 요청이 없으면 끝난다. 키가 사라지면 새 세션이다(#307).
 * serve_log 로 세지 않는다. 응답 뒤 비동기로 쓰고 유실을 허용해 연달아 온 요청에서 아직 안 쓰인 기록을 못 본다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ServeFrequencyRepository {

    static final String DAILY_KEY_PREFIX = "adserving:servedDaily:";
    static final String SESSION_KEY_PREFIX = "adserving:servedSession:";
    static final Duration SESSION_TTL = Duration.ofMinutes(30);

    // Valkey 가 멈춰 막지 않고 통과시킨 횟수다. 경보는 이 지표에 건다(장애 처리 가이드).
    static final String BYPASS_METRIC = "adserving_cache_bypass_total";

    private static final RedisScript<Long> RECORD_SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_record_served.lua"));
        script.setResultType(Long.class);
        return script;
    }

    static String dailyKey(LocalDate businessDate, Long memberId) {
        return DAILY_KEY_PREFIX + businessDate + ":" + memberId;
    }

    static String sessionKey(Long memberId) {
        return SESSION_KEY_PREFIX + memberId;
    }

    // Valkey 가 멈추면 빈 이력으로 통과시킨다. 같은 카드가 더 나올 뿐 과금과 권한은 바뀌지 않는다.
    public ServedHistory findHistory(LocalDate businessDate, Long memberId) {
        try {
            Map<Object, Object> daily = redisTemplate.opsForHash().entries(dailyKey(businessDate, memberId));
            Set<String> session = redisTemplate.opsForSet().members(sessionKey(memberId));
            return new ServedHistory(
                    daily.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                            e -> Long.valueOf((String) e.getKey()), e -> Integer.valueOf((String) e.getValue()))),
                    session == null ? Set.of()
                            : session.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet()));
        } catch (DataAccessException e) {
            log.warn("event=FEED_SERVE_FREQUENCY_READ_FAILED memberId={} cause={} 빈 이력으로 통과시킨다",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "serve_frequency").increment();
            return new ServedHistory(Map.of(), Set.of());
        }
    }

    // 응답할 카드의 캠페인을 올리고 세션을 늘린다. 실패해도 피드는 나간다. 다음 요청에서 같은 카드가 더 나올 수 있다.
    public void recordServed(LocalDate businessDate, Long memberId, Collection<Long> campaignIds, Instant now) {
        List<String> args = new ArrayList<>();
        // 하루 해시는 다음 영업일 00:00 에 끝난다
        args.add(String.valueOf(Duration.between(now, businessDate.plusDays(1).atStartOfDay(ClockConfig.ZONE)).toMillis()));
        args.add(String.valueOf(SESSION_TTL.toMillis()));
        campaignIds.forEach(id -> args.add(String.valueOf(id)));
        try {
            redisTemplate.execute(RECORD_SCRIPT, List.of(dailyKey(businessDate, memberId), sessionKey(memberId)),
                    args.toArray());
        } catch (DataAccessException e) {
            log.warn("event=FEED_SERVE_FREQUENCY_WRITE_FAILED memberId={} cause={} 세지 않고 통과시킨다",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "serve_frequency").increment();
        }
    }

    // 그날 캠페인별로 내려준 횟수와 이번 세션에 이미 내려준 캠페인이다.
    public record ServedHistory(Map<Long, Integer> dailyCounts, Set<Long> sessionCampaignIds) {
    }
}
