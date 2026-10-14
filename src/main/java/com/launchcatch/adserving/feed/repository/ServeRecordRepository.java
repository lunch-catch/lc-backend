package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.SlotType;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 카드마다의 serve 기록과 사용자 당일 이력을 Valkey 에 둔다.
 *
 * serve 기록은 노출과 스와이프 요청이 어느 서버로 가도 찾을 수 있어야 해서 JVM 이 아니라 Valkey 에 둔다(#236).
 * 당일 이력은 캠페인별로 그날 내려준 횟수와, 세션 동안 내려준 캠페인이다. 세션 키는 피드를 받을 때마다 30분으로 다시 늘어나므로
 * 30분 동안 피드를 받지 않으면 사라져 새 세션이 된다.
 */
@Repository
@RequiredArgsConstructor
public class ServeRecordRepository {

    public static final Duration SERVE_RECORD_TTL = Duration.ofMinutes(30);
    public static final Duration SESSION_IDLE = Duration.ofMinutes(30);

    private static final String SERVE_PREFIX = "adserving:serve:";
    private static final String DAILY_PREFIX = "adserving:served:";
    private static final String SESSION_PREFIX = "adserving:session:";

    // 영업일이 끝난 뒤에도 잠깐 남겨 자정 직전 요청의 기록이 읽히게 한다
    private static final Duration DAILY_GRACE = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public record ServeRecord(UUID serveId, Long campaignId, Long storeId, String categoryCode,
                              SlotType slotType, int slotPosition, LocalDateTime servedAt) {
    }

    // dailyCounts 는 그날 캠페인별로 내려준 횟수, sessionCampaignIds 는 이번 세션에 내려준 캠페인이다
    public record ServeHistory(Map<Long, Integer> dailyCounts, Set<Long> sessionCampaignIds) {
    }

    public ServeHistory findHistory(LocalDate businessDate, Long memberId) {
        Map<Object, Object> daily = redisTemplate.opsForHash().entries(dailyKey(businessDate, memberId));
        Set<String> session = redisTemplate.opsForSet().members(sessionKey(memberId));

        Map<Long, Integer> dailyCounts = new HashMap<>();
        daily.forEach((campaignId, count) ->
                dailyCounts.put(Long.valueOf((String) campaignId), Integer.valueOf((String) count)));
        Set<Long> sessionCampaignIds = new HashSet<>();
        if (session != null) {
            session.forEach(campaignId -> sessionCampaignIds.add(Long.valueOf(campaignId)));
        }
        return new ServeHistory(dailyCounts, sessionCampaignIds);
    }

    /*
     * 한 피드의 serve 기록과 당일 이력을 파이프라인 한 번으로 쓴다.
     * dailyTtl 은 지금부터 영업일이 끝날 때까지다. 실패하면 예외를 그대로 올린다.
     */
    public void save(LocalDate businessDate, Long memberId, List<ServeRecord> records, Duration dailyTtl) {
        String dailyKey = dailyKey(businessDate, memberId);
        String sessionKey = sessionKey(memberId);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (ServeRecord record : records) {
                    String serveKey = SERVE_PREFIX + record.serveId();
                    operations.opsForHash().putAll(serveKey, Map.of(
                            "memberId", String.valueOf(memberId),
                            "businessDate", businessDate.toString(),
                            "campaignId", String.valueOf(record.campaignId()),
                            "storeId", String.valueOf(record.storeId()),
                            "categoryCode", record.categoryCode(),
                            "slotType", record.slotType().name(),
                            "slotPosition", String.valueOf(record.slotPosition()),
                            "servedAt", record.servedAt().toString()));
                    operations.expire(serveKey, SERVE_RECORD_TTL);
                    operations.opsForHash().increment(dailyKey, String.valueOf(record.campaignId()), 1);
                    operations.opsForSet().add(sessionKey, String.valueOf(record.campaignId()));
                }
                operations.expire(dailyKey, dailyTtl.plus(DAILY_GRACE));
                operations.expire(sessionKey, SESSION_IDLE);
                return null;
            }
        });
    }

    private static String dailyKey(LocalDate businessDate, Long memberId) {
        return DAILY_PREFIX + businessDate + ":" + memberId;
    }

    private static String sessionKey(Long memberId) {
        return SESSION_PREFIX + memberId;
    }
}
