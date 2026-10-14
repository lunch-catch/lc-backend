package com.launchcatch.billing.cache;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.global.config.ClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/*
 * 캠페인 하루치 차감 판정 입력을 모은 Valkey 해시다. 정본은 budget_daily_plan 이고 이것은 미러다.
 *
 * 차감 핫패스가 Lua 한 번으로 판정하려면 예약액, 당일 소진, 중단 표시가 한 키에 있어야 한다.
 * 필드는 reserved, spend, paused, changedAt 이다. 이 판은 상태 변경 수신이 쓰는 paused 와 changedAt
 * 갱신만 둔다. 해시를 처음 만드는 일과 재구성은 00:00 예약과 노출 차감 작업과 함께 더한다.
 *
 * changedAt 은 KST 기준 epoch 마이크로초로 적는다. 문자열 시각은 LocalDateTime.toString 이 초나
 * 소수부를 생략할 때 길이가 달라져 사전순 비교가 틀린다.
 */
@Component
@RequiredArgsConstructor
public class ValkeyDailySpendCounter implements DailySpendCounter {

    private static final String KEY_PREFIX = "billing:day:";
    private static final String SERVED_PREFIX = "billing:served:";
    private static final List<String> READ_FIELDS = List.of("reserved", "spend");
    private static final String HASH_MISSING = "HASH_MISSING";
    private static final String REPLAY_PREFIX = "REPLAY:";

    /*
     * 처리 표시는 재호출이 올 수 있는 동안만 두면 된다. 이틀이면 넉넉하다.
     * 당일 해시는 영업일이 끝난 뒤 정산이 읽을 수 있게 사흘 둔다.
     */
    private static final long SERVED_TTL_SECONDS = Duration.ofDays(2).toSeconds();
    private static final RedisScript<Long> APPLY_STATUS_SCRIPT = loadApplyStatusScript();
    private static final long DAY_HASH_TTL_SECONDS = Duration.ofDays(3).toSeconds();
    private static final RedisScript<String> DEDUCT_SCRIPT = loadDeductScript();
    private static final RedisScript<Long> REBUILD_SCRIPT = loadRebuildScript();
    private static final RedisScript<Long> UNDO_SCRIPT = loadUndoScript();

    private final StringRedisTemplate redisTemplate;

    // 해시가 있고 저장된 시각보다 늦은 변경이면 중단 표시를 바꾸고 true 를 돌려준다
    @Override
    public boolean applyStatus(LocalDate businessDate, Long campaignId, boolean paused, LocalDateTime changedAt) {
        Long applied = redisTemplate.execute(APPLY_STATUS_SCRIPT,
                List.of(key(businessDate, campaignId)),
                paused ? "1" : "0",
                String.valueOf(epochMicros(changedAt)));
        return applied != null && applied == 1L;
    }

    // 해시를 지운다. 다음 차감이 DB 정본으로 다시 만든다
    @Override
    public void evict(LocalDate businessDate, Long campaignId) {
        redisTemplate.delete(key(businessDate, campaignId));
    }

    /*
     * 노출 하나를 판정한다. 같은 serveId 를 다시 부르면 처음 판정을 돌려주고 소진을 늘리지 않는다.
     * 해시가 없으면 아무것도 쓰지 않고 해시 없음을 돌려준다. 재구성은 부른 쪽이 정한다.
     *
     * 두 키를 한 스크립트에서 쓰므로 비클러스터 Valkey 를 전제한다. 클러스터 모드면 해시 태그가 필요하다.
     */
    @Override
    public DeductOutcome deduct(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
        String reply = redisTemplate.execute(DEDUCT_SCRIPT,
                List.of(key(businessDate, campaignId), servedKey(serveId)),
                String.valueOf(unitPrice),
                String.valueOf(SERVED_TTL_SECONDS));
        if (reply == null) {
            throw new IllegalStateException("판정 스크립트가 응답하지 않았습니다.");
        }
        if (HASH_MISSING.equals(reply)) {
            return DeductOutcome.hashMissing();
        }
        if (reply.startsWith(REPLAY_PREFIX)) {
            return DeductOutcome.replay(DeductionStatus.valueOf(reply.substring(REPLAY_PREFIX.length())));
        }
        return DeductOutcome.of(DeductionStatus.valueOf(reply));
    }

    /*
     * 이번 호출이 새로 남긴 판정을 되돌린다. 처리 표시를 지우고 BILLED 였으면 소진을 단가만큼 내린다.
     * 표시가 없으면 아무것도 하지 않고 false 다. Valkey 예외는 그대로 던진다. 부른 쪽이 기록하고 대사에 맡긴다.
     */
    @Override
    public boolean undo(LocalDate businessDate, Long campaignId, UUID serveId, long unitPrice) {
        Long undone = redisTemplate.execute(UNDO_SCRIPT,
                List.of(key(businessDate, campaignId), servedKey(serveId)),
                String.valueOf(unitPrice));
        return undone != null && undone == 1L;
    }

    /*
     * 해시가 없을 때만 DB 정본으로 만든다. 만들었으면 true, 이미 있어 그대로 두었으면 false 다.
     * changedAt 이 아직 없는 계획(상태가 바뀐 적 없음)은 0 으로 적어 어떤 변경이든 반영되게 한다.
     */
    @Override
    public boolean rebuildIfAbsent(LocalDate businessDate, Long campaignId, long reserved, long spend,
                                   boolean paused, LocalDateTime changedAt) {
        Long created = redisTemplate.execute(REBUILD_SCRIPT,
                List.of(key(businessDate, campaignId)),
                String.valueOf(reserved),
                String.valueOf(spend),
                paused ? "1" : "0",
                String.valueOf(changedAt == null ? 0L : epochMicros(changedAt)),
                String.valueOf(DAY_HASH_TTL_SECONDS));
        return created != null && created == 1L;
    }

    /*
     * 여러 캠페인의 예약액과 소진을 파이프라인 한 번으로 읽는다. 캠페인마다 나눠 부르지 않는다.
     * 해시가 없는 캠페인은 결과에 없다. 재구성 여부는 부른 쪽이 정한다.
     * Valkey 예외는 그대로 던진다. 대체값을 돌려주지 않는다.
     */
    @Override
    public Map<Long, DailySpend> readAll(LocalDate businessDate, Collection<Long> campaignIds) {
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = List.copyOf(campaignIds);
        List<Object> replies = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                HashOperations<String, String, String> hash = ((RedisOperations<String, String>) operations).opsForHash();
                for (Long campaignId : ids) {
                    hash.multiGet(key(businessDate, campaignId), READ_FIELDS);
                }
                return null;
            }
        });
        Map<Long, DailySpend> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            List<?> fields = (List<?>) replies.get(i);
            if (fields.get(0) != null) {
                result.put(ids.get(i), new DailySpend(
                        Long.parseLong((String) fields.get(0)),
                        fields.get(1) == null ? 0L : Long.parseLong((String) fields.get(1))));
            }
        }
        return result;
    }

    static String servedKey(UUID serveId) {
        return SERVED_PREFIX + serveId;
    }

    static String key(LocalDate businessDate, Long campaignId) {
        return KEY_PREFIX + businessDate + ":" + campaignId;
    }

    static long epochMicros(LocalDateTime time) {
        Instant instant = time.atZone(ClockConfig.ZONE).toInstant();
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static RedisScript<Long> loadApplyStatusScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/billing_day_status.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<String> loadDeductScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/billing_deduct.lua"));
        script.setResultType(String.class);
        return script;
    }

    private static RedisScript<Long> loadUndoScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/billing_deduct_undo.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<Long> loadRebuildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/billing_day_rebuild.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
