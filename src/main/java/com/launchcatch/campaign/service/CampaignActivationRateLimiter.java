package com.launchcatch.campaign.service;

import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/*
 * 점주 1명당 캠페인 활성화 요청을 분당 3회로 제한한다(#561 자체 리뷰로 찾음). 검수가 FAIL이어도
 * 매번 새로 Rekognition을 부르는데(점주의 재시도가 재시도 역할을 한다는 설계,
 * PosterModerationService 참고), 반복 요청을 막지 않으면 그 호출 비용과 쿼터를 그대로 소모한다.
 *
 * TemplateGenerationRateLimiter와 같은 고정 창 카운터(auth_rate_limit.lua 재사용)를 쓴다. 그
 * 리미터의 "같은 requestId 재시도" 표식은 활성화 요청엔 없는 개념이라 가져오지 않는다. 분당
 * 3회는 근거 없는 예시값이다(build-gate-guideline.md 기준으로는 C 등급). 실제 값은 운영 쪽과
 * 정한다.
 *
 * Redis 장애 시에는 세지 못하는 것뿐이지 막을 이유가 없어 통과시킨다
 * (장애_처리_설계_가이드.md, 캐시 기본 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignActivationRateLimiter {

    private static final String KEY_PREFIX = "campaignActivationRateLimit:";
    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public void checkLimit(Long ownerId) {
        String key = KEY_PREFIX + ownerId;
        try {
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
            if (count != null && count > LIMIT) {
                throw new CampaignException(CampaignErrorCode.ACTIVATION_RATE_LIMITED);
            }
        } catch (DataAccessException e) {
            log.warn("event=CAMPAIGN_ACTIVATION_RATE_LIMIT_CHECK_FAILED ownerId={} cause={} 통과시킨다",
                    ownerId, RedisFailureClassifier.causeLabel(e), e);
        }
    }
}
