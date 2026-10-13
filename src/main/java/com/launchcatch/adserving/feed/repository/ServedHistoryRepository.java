package com.launchcatch.adserving.feed.repository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 같은 사용자에게 같은 캠페인을 하루 2회, 한 세션 1회까지만 내려주려고 내려준 기록을 둔다(비즈니스 규칙).
 *
 * serve_log 로 세지 않는다. serve_log 는 응답 뒤 비동기로 쓰여 늦고 빠질 수 있다.
 * 세션은 마지막 피드 요청 뒤 30분 동안 이어진다. 세션 키의 만료를 요청마다 다시 거는 것으로 그 30분을 센다.
 * 원본이 따로 없다. 지워지면 그날 그 사용자에게 상한을 한 번 더 열어 줄 뿐이다.
 */
@Repository
@RequiredArgsConstructor
public class ServedHistoryRepository {

    static final Duration SESSION_IDLE = Duration.ofMinutes(30);

    // 그날이 끝난 뒤 저절로 지워지도록 이틀 뒤 만료한다
    private static final Duration DAY_TTL = Duration.ofDays(2);

    private static final String DAY_KEY_PREFIX = "adserving:feed:served:";
    private static final String SESSION_KEY_PREFIX = "adserving:feed:session:";

    private final StringRedisTemplate redisTemplate;

    public ServedHistory find(Long memberId, LocalDate businessDate) {
        Map<Object, Object> counts = redisTemplate.opsForHash().entries(dayKey(memberId, businessDate));
        Set<String> session = redisTemplate.opsForSet().members(sessionKey(memberId));
        return new ServedHistory(
                counts.entrySet().stream().collect(Collectors.toMap(
                        entry -> Long.valueOf((String) entry.getKey()),
                        entry -> Integer.valueOf((String) entry.getValue()))),
                session.stream().map(Long::valueOf).collect(Collectors.toSet()));
    }

    /*
     * 이번에 내려준 캠페인을 그날 횟수와 세션에 더한다.
     * 왕복을 줄이려고 한 번의 파이프라인으로 보낸다
     */
    public void record(Long memberId, LocalDate businessDate, Collection<Long> campaignIds) {
        String dayKey = dayKey(memberId, businessDate);
        String sessionKey = sessionKey(memberId);
        List<String> ids = campaignIds.stream().map(String::valueOf).toList();
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                ids.forEach(id -> operations.opsForHash().increment(dayKey, id, 1));
                operations.expire(dayKey, DAY_TTL);
                operations.opsForSet().add(sessionKey, ids.toArray());
                operations.expire(sessionKey, SESSION_IDLE);
                return null;
            }
        });
    }

    private static String dayKey(Long memberId, LocalDate businessDate) {
        return DAY_KEY_PREFIX + businessDate + ":" + memberId;
    }

    private static String sessionKey(Long memberId) {
        return SESSION_KEY_PREFIX + memberId;
    }
}
