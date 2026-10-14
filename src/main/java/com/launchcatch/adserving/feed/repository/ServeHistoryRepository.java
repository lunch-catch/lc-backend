package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.ServeRecord;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * serve 기록과, 같은 캠페인을 하루 2회 세션 1회까지만 내려주려고 세는 횟수를 Valkey 에 둔다.
 *
 * serve 기록은 노출 요청이 다른 인스턴스로 들어와도 찾을 수 있어야 해서 인스턴스 메모리에 두지 않는다.
 * 횟수는 serve_log 로 세지 않는다. serve_log 는 응답 뒤에 쓰여 바로 다음 요청에서 보이지 않을 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class ServeHistoryRepository {

    private static final Duration SERVE_RECORD_TTL = Duration.ofMinutes(30);
    private static final Duration SESSION_IDLE = Duration.ofMinutes(30);

    // 영업일이 바뀐 뒤 키가 남지 않을 만큼만 둔다. 날짜가 키에 있어 다음 날 횟수와 섞이지 않는다.
    private static final Duration TODAY_COUNT_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public ServeHistory find(Long memberId, LocalDate businessDate) {
        Map<Object, Object> rawCounts = redisTemplate.opsForHash().entries(todayKey(memberId, businessDate));
        Set<String> rawSession = redisTemplate.opsForSet().members(sessionKey(memberId));

        Map<Long, Integer> counts = new HashMap<>();
        rawCounts.forEach((campaignId, count) ->
                counts.put(Long.valueOf((String) campaignId), Integer.valueOf((String) count)));
        Set<Long> session = new HashSet<>();
        if (rawSession != null) {
            rawSession.forEach(campaignId -> session.add(Long.valueOf(campaignId)));
        }
        return new ServeHistory(counts, session);
    }

    // 카드마다 serve 기록을 쓰고 횟수를 올린다. 세션은 마지막 피드 요청에서 30분까지다.
    public void record(Long memberId, LocalDate businessDate, List<ServeRecord> records) {
        String todayKey = todayKey(memberId, businessDate);
        String sessionKey = sessionKey(memberId);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                for (ServeRecord record : records) {
                    String serveKey = serveKey(record);
                    ops.opsForHash().putAll(serveKey, toHash(record));
                    ops.expire(serveKey, SERVE_RECORD_TTL);
                    ops.opsForHash().increment(todayKey, String.valueOf(record.campaignId()), 1);
                    ops.opsForSet().add(sessionKey, String.valueOf(record.campaignId()));
                }
                ops.expire(todayKey, TODAY_COUNT_TTL);
                ops.expire(sessionKey, SESSION_IDLE);
                return null;
            }
        });
    }

    private static Map<String, String> toHash(ServeRecord record) {
        return Map.of(
                "feedId", record.feedId().toString(),
                "memberId", String.valueOf(record.memberId()),
                "businessDate", record.businessDate().toString(),
                "campaignId", String.valueOf(record.campaignId()),
                "storeId", String.valueOf(record.storeId()),
                "slotType", record.slotType().name(),
                "slotPosition", String.valueOf(record.slotPosition()),
                "servedAt", record.servedAt().toString());
    }

    private static String serveKey(ServeRecord record) {
        return "serve:" + record.serveId();
    }

    private static String todayKey(Long memberId, LocalDate businessDate) {
        return "feedServed:" + businessDate + ":" + memberId;
    }

    private static String sessionKey(Long memberId) {
        return "feedSession:" + memberId;
    }
}
