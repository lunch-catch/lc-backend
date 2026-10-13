package com.launchcatch.billing.repository;

import com.launchcatch.global.config.ClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

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
@Repository
@RequiredArgsConstructor
public class DailySpendHashRepository {

    private static final String KEY_PREFIX = "billing:day:";
    private static final RedisScript<Long> APPLY_STATUS_SCRIPT = loadApplyStatusScript();

    private final StringRedisTemplate redisTemplate;

    // 해시가 있고 저장된 시각보다 늦은 변경이면 중단 표시를 바꾸고 true 를 돌려준다
    public boolean applyStatus(LocalDate businessDate, Long campaignId, boolean paused, LocalDateTime changedAt) {
        Long applied = redisTemplate.execute(APPLY_STATUS_SCRIPT,
                List.of(key(businessDate, campaignId)),
                paused ? "1" : "0",
                String.valueOf(epochMicros(changedAt)));
        return applied != null && applied == 1L;
    }

    // 해시를 지운다. 다음 차감이 DB 정본으로 다시 만든다
    public void evict(LocalDate businessDate, Long campaignId) {
        redisTemplate.delete(key(businessDate, campaignId));
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
}
