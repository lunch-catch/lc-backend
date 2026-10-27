package com.lunchcatch.adserving.feed.repository;

import static com.lunchcatch.adserving.exclusion.repository.ExclusionRepository.BYPASS_METRIC;

import com.lunchcatch.auth.RedisFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 캠페인별 그날 유효 노출 수다. 노출 상한(그날 예약액 / 단가 x 1.05)을 판정할 때 읽는다(FR-096).
 * 요청마다 impression_log 를 GROUP BY 로 세면 500 TPS 에서 후보 수백 개의 집계가 매번 돌아 카운터를 둔다(#307).
 * 올리는 쪽은 노출 API 다. 유효 노출을 처음 저장할 때 올린다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ImpressionCountRepository {

    static final String KEY_PREFIX = "adserving:validImpressions:";

    // 피드는 그날 키만 읽는다. 다음 날까지 남겨 두면 대조할 때 볼 수 있어 이틀 뒤에 지운다.
    static final Duration TTL = Duration.ofDays(2);

    private static final RedisScript<Long> INCREMENT_SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    static String key(LocalDate businessDate) {
        return KEY_PREFIX + businessDate;
    }

    /*
     * 그 캠페인의 그날 유효 노출 수를 1 올린다. 처음 저장된 유효 노출이 커밋된 뒤에만 부른다.
     * Valkey 가 멈추면 올리지 않고 센다. 노출 상한이 그만큼 늦게 걸릴 뿐 과금은 정산이 막는다.
     */
    public void increment(LocalDate businessDate, Long campaignId) {
        try {
            // 올리기와 만료를 한 번에 보낸다. 만료만 실패해 키가 TTL 없이 남거나 올렸는데 못 올렸다고 세지 않게 한다(#783)
            redisTemplate.execute(INCREMENT_SCRIPT, List.of(key(businessDate)),
                    String.valueOf(TTL.toMillis()), String.valueOf(campaignId));
        } catch (DataAccessException e) {
            log.warn("event=ADSERVING_FEED_IMPRESSION_COUNT_INCREMENT_FAILED campaignId={} reason={} fallback=SKIP_WRITE",
                    campaignId, RedisFailureClassifier.causeLabel(e), e);
            meterRegistry.counter(BYPASS_METRIC, "use", "impression_count").increment();
        }
    }

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/feed_impression_count_increment.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
