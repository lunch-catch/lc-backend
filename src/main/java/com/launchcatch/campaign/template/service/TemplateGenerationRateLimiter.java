package com.launchcatch.campaign.template.service;

import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
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
 * 관리자 1명당 템플릿 생성/수정 요청을 분당 3회로 제한한다(#227). requestId 멱등성은 같은
 * 요청의 네트워크 재시도만 막고, 관리자가 매번 새 requestId로 "다시 생성"을 반복 누르는 건
 * 못 막는다. Bedrock 호출은 실제 과금되고 재시도까지 포함해 최대 60초가 걸릴 수 있어
 * 별도로 막는다.
 *
 * AuthRateLimitFilter와 같은 고정 창 카운터(auth_rate_limit.lua 재사용)를 쓴다. 다만
 * AuthRateLimitFilter는 인증 전 요청이라 IP 기준 필터인 반면, 여기는 이미 인증된 관리자라
 * 관리자 ID 기준으로 서비스 계층에서 센다(feed 설계 문서의 선례와 같은 이유).
 *
 * Redis 장애 시에는 세지 못하는 것뿐이지 막을 이유가 없어 통과시킨다
 * (장애_처리_설계_가이드.md, 캐시 기본 처리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateGenerationRateLimiter {

    private static final String KEY_PREFIX = "templateGenerationRateLimit:";
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

    public void checkLimit(Long adminId) {
        if (isOverLimit(adminId)) {
            throw new CampaignException(PosterErrorCode.GENERATION_RATE_LIMITED);
        }
    }

    private boolean isOverLimit(Long adminId) {
        String key = KEY_PREFIX + adminId;
        try {
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
            return count != null && count > LIMIT;
        } catch (DataAccessException e) {
            log.warn("event=TEMPLATE_GENERATION_RATE_LIMIT_CHECK_FAILED adminId={} cause={} 통과시킨다",
                    adminId, RedisFailureClassifier.causeLabel(e), e);
            return false;
        }
    }
}
