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
 *
 * TemplateCreateService/TemplateReviseService는 저장된 버전이 있을 때만 requestId 재시도로
 * 본다. 같은 requestId가 저장되기 전에(= LLM 호출이 끝나기 전에) 다시 들어오면 그 조회가
 * 둘 다 비어 있다. 이 경우를 SETNX로 "이미 처리 중인 requestId"로 표시해 가린다. TTL은
 * 이 LLM 호출의 실제 worst-case(BedrockConfig.API_CALL_TIMEOUT 30초 x
 * BedrockTemplateHtmlGenerator.MAX_RETRIES+1회 = 60초)에 맞춘다.
 *
 * 이미 처리 중인 requestId면 속도 제한 횟수를 세지 않고(코드래빗 리뷰로 찾음) POSTER-004로
 * 바로 거부한다. 조용히 통과시키면 호출한 쪽(TemplateCreateService/TemplateReviseService)이
 * 그대로 LLM을 또 불러 과금과 외부 호출이 중복된다(코드래빗 리뷰로 다시 찾음). 저장 시점
 * request_id UNIQUE 제약은 중복 저장만 막을 뿐 중복 호출 자체는 못 막아서, 여기서 미리 막는
 * 것이 더 싸다. 클라이언트는 POSTER-004 응답을 받고 잠시 후 다시 요청하면 되고, 그때는
 * 첫 요청이 저장을 끝냈을 가능성이 높아 requestId 재시도 조회로 바로 응답받는다.
 *
 * 표식은 제한을 통과한 요청에만 남긴다. isOverLimit 이 거부로 판정되면 방금 세운 표식을
 * 바로 지운다(코드래빗 리뷰로 찾음). 안 지우면 거부된 요청의 재시도가 "이미 표식 있음"으로
 * isOverLimit 자체를 건너뛰고 POSTER-004 로만 응답받아, 그 재시도가 분당 3회 제한에
 * 걸렸다는 걸 클라이언트가 알 수 없게 된다. 지우는 순간과 진짜 동시에 들어온 재시도까지
 * 막는 건 아니지만(SETNX와 거부 판정, 지움이 하나의 원자 연산은 아니다), 그 틈은 저장
 * 시점 DB UNIQUE 로 거르는 기존 경쟁(TemplateCreateService 의 request_id 충돌)과 같은
 * 급의 드문 경우라 별도 락을 걸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateGenerationRateLimiter {

    private static final String KEY_PREFIX = "templateGenerationRateLimit:";
    private static final String IN_FLIGHT_KEY_PREFIX = "templateGenerationInFlight:";
    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration IN_FLIGHT_TTL = Duration.ofSeconds(60);
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public void checkLimit(Long adminId, String requestId) {
        if (!isFirstAttempt(requestId)) {
            throw new CampaignException(PosterErrorCode.GENERATION_TIMEOUT);
        }
        if (isOverLimit(adminId)) {
            clearInFlight(requestId);
            throw new CampaignException(PosterErrorCode.GENERATION_RATE_LIMITED);
        }
    }

    /*
     * 요청 문구 검증(TemplatePromptValidator)이 거부한 요청의 "처리 중" 표식을 지운다(#428). 거부된 요청도
     * 분당 3회에는 센다. 비용이 들지 않는 거부 응답으로 우회 패턴을 무제한 시험하지 못하게 하려는 것이다.
     * 다만 모델을 부르지 않았으므로 표식은 남기지 않는다. 남기면 문구를 고친 같은 requestId 재요청이
     * TTL 동안 POSTER-004 로만 거부된다.
     */
    public void releaseInFlight(String requestId) {
        clearInFlight(requestId);
    }

    private boolean isFirstAttempt(String requestId) {
        String key = IN_FLIGHT_KEY_PREFIX + requestId;
        try {
            Boolean firstAttempt = redisTemplate.opsForValue().setIfAbsent(key, "1", IN_FLIGHT_TTL);
            return !Boolean.FALSE.equals(firstAttempt);
        } catch (DataAccessException e) {
            log.warn("event=TEMPLATE_GENERATION_IN_FLIGHT_CHECK_FAILED requestId={} reason={} fallback=COUNT_AS_FIRST_ATTEMPT",
                    requestId, RedisFailureClassifier.causeLabel(e), e);
            return true;
        }
    }

    private void clearInFlight(String requestId) {
        try {
            redisTemplate.delete(IN_FLIGHT_KEY_PREFIX + requestId);
        } catch (DataAccessException e) {
            log.warn("event=TEMPLATE_GENERATION_IN_FLIGHT_CLEAR_FAILED requestId={} reason={} fallback=WAIT_FOR_TTL",
                    requestId, RedisFailureClassifier.causeLabel(e), e);
        }
    }

    private boolean isOverLimit(Long adminId) {
        String key = KEY_PREFIX + adminId;
        try {
            Long count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
            return count != null && count > LIMIT;
        } catch (DataAccessException e) {
            log.warn("event=TEMPLATE_GENERATION_RATE_LIMIT_CHECK_FAILED adminId={} reason={} fallback=PASS",
                    adminId, RedisFailureClassifier.causeLabel(e), e);
            return false;
        }
    }
}
