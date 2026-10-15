package com.launchcatch.adserving.feed.repository;

import com.launchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 사용자가 그날 찜하거나 패스해서 피드에서 뺄 캠페인의 집합이다(FR-099 (9), BR-040).
 * 찜 목록에서 지운 캠페인도 남아 그날 다시 나오지 않는다. 그래서 swipe_log 와 wishlist 로 대신하지 않는다.
 * 쓰는 쪽은 스와이프와 찜 API 다. 키에 영업일이 있어 00:00 에 저절로 비고, 만료는 쓰는 쪽이 건다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DailyExclusionRepository {

    static final String KEY_PREFIX = "adserving:dailyExclusion:";

    // Valkey 가 멈춰 막지 않고 통과시킨 횟수다. 경보는 이 지표에 건다(장애 처리 가이드).
    static final String BYPASS_METRIC = "adserving_cache_bypass_total";

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
            log.warn("event=FEED_DAILY_EXCLUSION_FAILED memberId={} cause={} 빈 이력으로 통과시킨다",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "daily_exclusion").increment();
            return Set.of();
        }
    }
}
