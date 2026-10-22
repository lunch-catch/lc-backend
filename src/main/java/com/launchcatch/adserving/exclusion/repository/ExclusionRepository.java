package com.launchcatch.adserving.exclusion.repository;

import com.launchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import com.launchcatch.global.config.ClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * 사용자가 그날 찜하거나 패스해서 피드에서 뺄 캠페인의 집합이다(FR-099 (9), BR-040).
 * 찜 목록에서 지운 캠페인도 남아 그날 다시 나오지 않는다. 그래서 swipe_log 와 wishlist 로 대신하지 않는다.
 * 쓰는 쪽은 스와이프와 찜 API 다. 키에 영업일이 있어 00:00 에 저절로 비고, 만료는 다음 영업일 00:00 까지 남은 시간으로 건다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ExclusionRepository {

    static final String KEY_PREFIX = "adserving:exclusion:";

    /*
     * Valkey 가 멈춰 막지 않고 통과시킨 횟수다. 경보는 이 지표에 건다(장애 처리 가이드).
     * 광고 서빙의 Valkey 저장소가 모두 이 이름을 쓰고 use 태그로 쓰임새를 가른다(#654).
     */
    public static final String BYPASS_METRIC = "adserving_cache_bypass_total";

    private static final RedisScript<Long> ADD_SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    static String key(LocalDate businessDate, Long memberId) {
        return KEY_PREFIX + businessDate + ":" + memberId;
    }

    // Valkey 가 멈추면 빈 집합으로 통과시킨다. 이미 넘긴 카드가 다시 보일 뿐 틀린 과금이나 권한으로 이어지지 않는다.
    public Set<Long> findCampaignIds(LocalDate businessDate, Long memberId) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(key(businessDate, memberId));
            if (members == null) {
                return Set.of();
            }
            return members.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_EXCLUSION_FAILED memberId={} reason={} fallback=EMPTY_HISTORY",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "exclusion").increment();
            return Set.of();
        }
    }

    /*
     * 그날 피드에서 뺄 캠페인을 더한다. 만료를 시각이 아니라 남은 시간으로 건다. 앱의 영업일과 Valkey 의 시계가 어긋나도 키가 곧바로 사라지지 않는다.
     * Valkey 가 멈추면 넣지 않고 센다. 그날 그 카드가 다시 보일 뿐이다.
     */
    public void add(LocalDate businessDate, Long memberId, Long campaignId, Instant now) {
        String key = key(businessDate, memberId);
        try {
            // 넣기와 만료를 한 번에 보낸다. 만료만 실패해 키가 TTL 없이 남거나 넣었는데 못 넣었다고 세지 않게 한다(#783)
            Duration remaining = Duration.between(now, businessDate.plusDays(1).atStartOfDay(ClockConfig.ZONE));
            redisTemplate.execute(ADD_SCRIPT, List.of(key), String.valueOf(remaining.toMillis()), String.valueOf(campaignId));
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_EXCLUSION_WRITE_FAILED memberId={} campaignId={} reason={} fallback=SKIP_WRITE",
                    memberId, campaignId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "exclusion").increment();
        }
    }

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_exclusion_add.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
