package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.ServeRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * serve 기록을 30분 둔다. 노출 수집이 이 기록으로 유효 노출을 판정한다(100행).
 * 카드를 받은 지 30분이 지나면 키가 사라져 그 노출은 EXPIRED_OR_UNKNOWN 이 된다.
 * 원본은 serve_log 이지만 판정은 이 키로만 한다. serve_log 는 비동기라 판정 시점에 없을 수 있다.
 */
@Repository
@RequiredArgsConstructor
public class ServeRecordCacheRepository {

    static final Duration TTL = Duration.ofMinutes(30);
    static final String KEY_PREFIX = "adserving:serve:";

    private final StringRedisTemplate redisTemplate;

    // 피드 한 번의 카드를 한 번의 파이프라인으로 쓴다
    public void saveAll(List<ServeRecord> records) {
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (ServeRecord record : records) {
                    String key = KEY_PREFIX + record.serveId();
                    operations.opsForHash().putAll(key, fields(record));
                    operations.expire(key, TTL);
                }
                return null;
            }
        });
    }

    private static Map<String, String> fields(ServeRecord record) {
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
}
