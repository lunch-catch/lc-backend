package com.launchcatch.adserving.feed.repository;


import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 카드마다의 serve 기록이다. 노출 요청이 이 기록으로 검증된다. 30분이 지나면 사라져 그 뒤의 노출은 EXPIRED_OR_UNKNOWN 이다.
 * 서버 여러 대가 함께 보도록 Valkey 에 둔다. 서버별 캐시면 노출 요청이 다른 서버로 갈 때 기록을 못 찾는다(#315).
 * 값은 회원:캠페인:가게:슬롯:자리:내려준 시각(ms) 을 콜론으로 이은 문자열이다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ServeRecordRepository {

    static final String KEY_PREFIX = "adserving:serve:";
    static final Duration TTL = Duration.ofMinutes(30);

    // Valkey 가 멈춰 막지 않고 통과시킨 횟수다. 경보는 이 지표에 건다(장애 처리 가이드).
    static final String BYPASS_METRIC = "adserving_cache_bypass_total";

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    static String key(UUID serveId) {
        return KEY_PREFIX + serveId;
    }

    /*
     * 피드 1회의 카드를 파이프라인 한 번으로 쓴다. 응답 전에 불러야 곧바로 온 노출이 기록을 찾는다.
     * Valkey 가 멈추면 쓰지 않고 피드를 그대로 내보낸다. 그동안의 노출은 무효가 되어 손해는 플랫폼에 남는다.
     */
    public void saveAll(List<ServeRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> strings = (RedisOperations<String, String>) operations;
                    records.forEach(r -> strings.opsForValue().set(key(r.serveId()), encode(r), TTL));
                    return null;
                }
            });
        } catch (DataAccessException e) {
            log.warn("event=FEED_SERVE_RECORD_WRITE_FAILED cards={} cause={} 기록 없이 내보낸다",
                    records.size(), RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "serve_record").increment();
        }
    }

    /*
     * 노출 검증이 부른다. 없거나 30분이 지났으면 비어 있다.
     * Valkey 가 멈추면 기록 없음으로 보고 센다. 그 노출은 EXPIRED_OR_UNKNOWN 으로 저장되고 과금되지 않는다.
     */
    public Optional<ServeRecord> findById(UUID serveId) {
        try {
            String value = redisTemplate.opsForValue().get(key(serveId));
            return Optional.ofNullable(value).map(v -> decode(serveId, v));
        } catch (DataAccessException e) {
            log.warn("event=FEED_SERVE_RECORD_READ_FAILED serveId={} cause={} 기록 없음으로 본다",
                    serveId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "serve_record").increment();
            return Optional.empty();
        }
    }

    static String encode(ServeRecord r) {
        return String.join(":", String.valueOf(r.memberId()), String.valueOf(r.campaignId()),
                String.valueOf(r.storeId()), r.slotType().name(), String.valueOf(r.slotPosition()),
                String.valueOf(r.servedAt().toEpochMilli()));
    }

    static ServeRecord decode(UUID serveId, String value) {
        String[] parts = value.split(":");
        return new ServeRecord(serveId, Long.valueOf(parts[0]), Long.valueOf(parts[1]), Long.valueOf(parts[2]),
                SlotType.valueOf(parts[3]), Integer.parseInt(parts[4]), Instant.ofEpochMilli(Long.parseLong(parts[5])));
    }
}
