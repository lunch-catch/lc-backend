package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class TemplateGenerationRateLimiterTest {

    private static final String REQUEST_ID = "req-1";
    private static final String IN_FLIGHT_KEY = "templateGenerationInFlight:" + REQUEST_ID;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TemplateGenerationRateLimiter rateLimiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        rateLimiter = new TemplateGenerationRateLimiter(redisTemplate);
    }

    private void 처음_보는_requestId로_설정한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    @Test
    @DisplayName("분당 3회 이하면 거부하지 않는다")
    void 분당_삼회_이하면_거부하지_않는다() {
        처음_보는_requestId로_설정한다();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

        assertThatCode(() -> rateLimiter.checkLimit(1L, REQUEST_ID)).doesNotThrowAnyException();

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("요청 문구 검증이 거부한 requestId 의 진행 중 표시를 지운다(#428)")
    void 진행_중_표시를_풀어준다() {
        rateLimiter.releaseInFlight(REQUEST_ID);

        verify(redisTemplate).delete(IN_FLIGHT_KEY);
    }

    @Test
    @DisplayName("분당 3회를 넘으면 POSTER-014을 던지고, 남긴 진행 중 표시를 지운다")
    void 분당_삼회를_넘으면_거부한다() {
        처음_보는_requestId로_설정한다();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);

        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, REQUEST_ID))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_RATE_LIMITED);

        verify(redisTemplate).delete(IN_FLIGHT_KEY);
    }

    @Test
    @DisplayName("거부된 요청의 재시도는 지워진 표시 덕에 다시 거부 판정을 받는다")
    void 거부된_요청의_재시도는_다시_거부_판정을_받는다() {
        처음_보는_requestId로_설정한다();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);
        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, REQUEST_ID)).isInstanceOf(CampaignException.class);
        verify(redisTemplate).delete(IN_FLIGHT_KEY);

        /*
         * 표시가 지워졌으니 재시도는 다시 "처음 보는 요청"으로 SETNX를 통과하고,
         * isOverLimit을 건너뛰지 않는다. 실제 Redis라면 지워진 키라 SETNX가 다시
         * 성공하는 것과 같은 상황을 그대로 스텁으로 표현한다.
         */
        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, REQUEST_ID)).isInstanceOf(CampaignException.class);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("Redis 장애면 세지 못할 뿐이라 통과시킨다(장애_처리_설계_가이드.md 캐시 기본 처리)")
    void Redis_장애면_통과시킨다() {
        처음_보는_requestId로_설정한다();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimiter.checkLimit(1L, REQUEST_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 requestId가 이미 진행 중이면 POSTER-004로 거부하고 호출 횟수를 세지 않는다")
    void 같은_requestId가_진행_중이면_POSTER_004로_거부한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, REQUEST_ID))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_TIMEOUT);

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("진행 중 표시 확인에 Redis 장애가 나면 처음 시도로 간주해 센다")
    void 진행중_표시_확인이_Redis_장애면_처음_시도로_간주한다() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);

        assertThatThrownBy(() -> rateLimiter.checkLimit(1L, REQUEST_ID))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_RATE_LIMITED);
    }
}
