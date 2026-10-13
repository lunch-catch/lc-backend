package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.AssignedCard;
import com.launchcatch.adserving.feed.ServedFeed;
import com.launchcatch.adserving.feed.ServedFeed.ServedCard;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 피드가 내려간 뒤 Valkey 에 남기는 두 가지다.
 * serve 기록은 카드마다 30분 남아 노출 검증이 읽는다(100행).
 * 회원 당일 이력은 같은 캠페인을 하루 2회, 한 세션 1회까지만 내려주는 데 쓴다.
 * 세션 키는 피드를 받을 때마다 30분을 다시 걸어, 30분 동안 요청이 없으면 사라진다.
 */
@Repository
@RequiredArgsConstructor
public class ServeCacheRepository {

    public static final Duration SERVE_RECORD_TTL = Duration.ofMinutes(30);
    public static final Duration SESSION_TTL = Duration.ofMinutes(30);

    // 영업일이 바뀌면 새 키를 쓰므로 하루보다 조금 길게 두고 지운다
    private static final Duration DAILY_TTL = Duration.ofHours(26);

    private static final String SERVE_KEY_PREFIX = "adserving:serve:";
    private static final String DAILY_KEY_PREFIX = "adserving:served:";
    private static final String SESSION_KEY_PREFIX = "adserving:session:";

    private final StringRedisTemplate redisTemplate;

    public ServeHistory findHistory(Long memberId, LocalDate businessDate) {
        Map<Object, Object> daily = redisTemplate.opsForHash().entries(dailyKey(memberId, businessDate));
        Set<String> session = redisTemplate.opsForSet().members(sessionKey(memberId));
        Map<Long, Long> servedToday = new HashMap<>();
        daily.forEach((campaignId, count) -> servedToday.put(Long.valueOf((String) campaignId), Long.valueOf((String) count)));
        Set<Long> servedInSession = new HashSet<>();
        session.forEach(campaignId -> servedInSession.add(Long.valueOf(campaignId)));
        return new ServeHistory(servedToday, servedInSession);
    }

    /*
     * 카드마다 serve 기록을 쓰고 당일 이력과 세션에 더한다.
     * 왕복을 한 번으로 줄이려고 파이프라인으로 보낸다.
     */
    public void record(ServedFeed feed) {
        String dailyKey = dailyKey(feed.memberId(), feed.businessDate());
        String sessionKey = sessionKey(feed.memberId());
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> commands = (RedisOperations<String, String>) operations;
                for (ServedCard served : feed.cards()) {
                    String serveKey = SERVE_KEY_PREFIX + served.serveId();
                    commands.opsForHash().putAll(serveKey, serveRecord(feed, served.card()));
                    commands.expire(serveKey, SERVE_RECORD_TTL);
                    String campaignId = String.valueOf(served.card().candidate().campaignId());
                    commands.opsForHash().increment(dailyKey, campaignId, 1);
                    commands.opsForSet().add(sessionKey, campaignId);
                }
                commands.expire(dailyKey, DAILY_TTL);
                commands.expire(sessionKey, SESSION_TTL);
                return null;
            }
        });
    }

    private static Map<String, String> serveRecord(ServedFeed feed, AssignedCard card) {
        return Map.of(
                "memberId", String.valueOf(feed.memberId()),
                "businessDate", feed.businessDate().toString(),
                "servedAt", feed.servedAt().toString(),
                "campaignId", String.valueOf(card.candidate().campaignId()),
                "storeId", String.valueOf(card.candidate().storeId()),
                "slotType", card.slotType().name(),
                "slotPosition", String.valueOf(card.slotPosition()));
    }

    static String dailyKey(Long memberId, LocalDate businessDate) {
        return DAILY_KEY_PREFIX + businessDate + ":" + memberId;
    }

    static String sessionKey(Long memberId) {
        return SESSION_KEY_PREFIX + memberId;
    }

    /*
     * 회원이 그날 캠페인마다 받은 횟수와 지금 세션에서 받은 캠페인이다.
     * 캐시를 읽지 못한 회차에는 EMPTY 를 쓴다.
     */
    public record ServeHistory(Map<Long, Long> servedToday, Set<Long> servedInSession) {

        public static final ServeHistory EMPTY = new ServeHistory(Map.of(), Set.of());

        public ServeHistory {
            servedToday = Map.copyOf(servedToday);
            servedInSession = Set.copyOf(servedInSession);
        }

        // 그날 받은 횟수가 상한 아래이고 이번 세션에 받은 적이 없으면 참이다
        public boolean allows(Long campaignId, long dailyCap) {
            return servedToday.getOrDefault(campaignId, 0L) < dailyCap && !servedInSession.contains(campaignId);
        }
    }
}
