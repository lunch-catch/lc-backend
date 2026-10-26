package com.launchcatch.adserving.feed.repository;

import static com.launchcatch.adserving.exclusion.repository.ExclusionRepository.BYPASS_METRIC;

import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository.ServedHistory;
import com.launchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 피드가 배정 전에 Valkey 에서 읽는 다섯(하루와 세션 횟수, 당일 이력, 유효 노출 수, 필터, 30일 찜)을 파이프라인 하나로 읽는다(#1042 결정 5).
 * 하나씩 읽으면 왕복이 다섯이다.
 * 파이프라인이 실패하면 다섯 모두 지금의 우회값(빈 이력, 0 건, 캐시 없음)을 쓰고 쓰임새마다 센다. Valkey 실패는 대개 연결 단위라 한꺼번에 실패한다.
 *
 * 필터와 30일 찜은 DB 가 원본이고 여기는 캐시다(#1042 결정 4). 없으면 부르는 쪽이 DB 에서 읽어 채운다.
 * 필터를 저장하면 필터 키를, 찜 스와이프를 하면 그날 찜 키를 지운다. 지우지 못하면 만료(하루)까지 낡은 값이 남을 수 있다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FeedStateRepository {

    static final String FILTER_KEY_PREFIX = "adserving:feedFilter:";
    static final String WISH_KEY_PREFIX = "adserving:feedWishes:";
    static final Duration CACHE_TTL = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    static String filterKey(Long memberId) {
        return FILTER_KEY_PREFIX + memberId;
    }

    static String wishKey(LocalDate businessDate, Long memberId) {
        return WISH_KEY_PREFIX + businessDate + ":" + memberId;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FeedState read(LocalDate businessDate, Long memberId, List<Long> campaignIds) {
        List<Object> fields = campaignIds.stream().<Object>map(String::valueOf).toList();
        try {
            List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                public Object execute(RedisOperations operations) {
                    operations.opsForHash().entries(ServeFrequencyRepository.dailyKey(businessDate, memberId));
                    operations.opsForSet().members(ServeFrequencyRepository.sessionKey(memberId));
                    operations.opsForSet().members(ExclusionRepository.key(businessDate, memberId));
                    if (!fields.isEmpty()) {
                        operations.opsForHash().multiGet(ImpressionCountRepository.key(businessDate), fields);
                    }
                    operations.opsForValue().get(filterKey(memberId));
                    operations.opsForValue().get(wishKey(businessDate, memberId));
                    return null;
                }
            });
            int i = 0;
            Map<Object, Object> daily = (Map<Object, Object>) results.get(i++);
            Set<String> session = (Set<String>) results.get(i++);
            Set<String> excluded = (Set<String>) results.get(i++);
            List<Object> counts = fields.isEmpty() ? List.of() : (List<Object>) results.get(i++);
            String filter = (String) results.get(i++);
            String wishes = (String) results.get(i);
            return new FeedState(history(daily, session), ids(excluded), validCounts(campaignIds, counts),
                    filter == null ? null : CachedFilter.parse(filter), wishes == null ? null : parseWishes(wishes));
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_STATE_READ_FAILED memberId={} reason={} fallback=EMPTY_STATE",
                    memberId, RedisFailureClassifier.causeLabel(e), e);
            for (String use : List.of("serve_frequency", "exclusion", "impression_count", "feed_filter", "feed_wishes")) {
                meterRegistry.counter(BYPASS_METRIC, "use", use).increment();
            }
            return new FeedState(new ServedHistory(Map.of(), Set.of()), Set.of(), Map.of(), null, null);
        }
    }

    // DB 에서 읽은 필터를 캐시에 둔다. 쓰지 못해도 다음 피드가 다시 DB 에서 읽을 뿐이다.
    public void cacheFilter(Long memberId, CachedFilter filter) {
        write(filterKey(memberId), filter.format(), "feed_filter");
    }

    // DB 에서 센 그날의 30일 찜을 캐시에 둔다.
    public void cacheWishes(LocalDate businessDate, Long memberId, List<CategoryWishCount> wishes) {
        write(wishKey(businessDate, memberId), formatWishes(wishes), "feed_wishes");
    }

    // 필터를 저장했으면 지운다. 다음 피드가 DB 에서 다시 읽는다.
    public void evictFilter(Long memberId) {
        delete(filterKey(memberId), "feed_filter");
    }

    // 찜 스와이프가 기록됐으면 그날 찜을 지운다.
    public void evictWishes(LocalDate businessDate, Long memberId) {
        delete(wishKey(businessDate, memberId), "feed_wishes");
    }

    private void write(String key, String value, String use) {
        try {
            redisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_STATE_WRITE_FAILED key={} reason={} fallback=SKIP_CACHE",
                    key, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", use).increment();
        }
    }

    private void delete(String key, String use) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_STATE_EVICT_FAILED key={} reason={} fallback=STALE_UNTIL_EXPIRY",
                    key, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", use).increment();
        }
    }

    private static ServedHistory history(Map<Object, Object> daily, Set<String> session) {
        return new ServedHistory(
                daily == null ? Map.of() : daily.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        e -> Long.valueOf((String) e.getKey()), e -> Integer.valueOf((String) e.getValue()))),
                ids(session));
    }

    private static Set<Long> ids(Set<String> members) {
        return members == null ? Set.of() : members.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    private static Map<Long, Long> validCounts(List<Long> campaignIds, List<Object> values) {
        Map<Long, Long> counts = new HashMap<>();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                counts.put(campaignIds.get(i), Long.valueOf((String) values.get(i)));
            }
        }
        return counts;
    }

    // 업종:건수를 쉼표로 잇는다. 찜이 없으면 빈 문자열이고 캐시가 없는 것(null)과 다르다.
    static String formatWishes(List<CategoryWishCount> wishes) {
        return wishes.stream().map(w -> w.categoryCode() + ":" + w.count()).collect(Collectors.joining(","));
    }

    static List<CategoryWishCount> parseWishes(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(pair -> pair.split(":"))
                .map(p -> new CategoryWishCount(p[0], Long.parseLong(p[1])))
                .toList();
    }

    /*
     * 배정 전에 읽은 회원의 상태다. filter 와 wishes 는 캐시가 없으면 null 이다.
     * 파이프라인이 실패하면 이력과 노출 수는 비고 filter 와 wishes 는 null 이다.
     */
    public record FeedState(ServedHistory history, Set<Long> excludedCampaignIds, Map<Long, Long> validCounts,
                            CachedFilter filter, List<CategoryWishCount> wishes) {
    }

    // 정렬과 업종을 "정렬|업종,업종" 으로 둔다. 필터를 저장하지 않은 회원은 기본값(거리순, 전체 업종)이다.
    public record CachedFilter(FeedSort sort, Set<String> categoryCodes) {

        String format() {
            return sort.name() + "|" + String.join(",", categoryCodes);
        }

        static CachedFilter parse(String value) {
            int bar = value.indexOf('|');
            String codes = value.substring(bar + 1);
            return new CachedFilter(FeedSort.valueOf(value.substring(0, bar)),
                    codes.isEmpty() ? Set.of() : Set.of(codes.split(",")));
        }
    }
}
