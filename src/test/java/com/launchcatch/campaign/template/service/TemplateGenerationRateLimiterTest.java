package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class TemplateGenerationRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private TemplateGenerationRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new TemplateGenerationRateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("분당 3회 이하면 거부하지 않는다")
    void 분당_삼회_이하면_거부하지_않는다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

        assertThatCode(() -> rateLimiter.checkLimit(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("분당 3회를 넘으면 POSTER-010을 던진다")
    void 분당_삼회를_넘으면_거부한다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(4L);

        assertThatThrownBy(() -> rateLimiter.checkLimit(1L))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_RATE_LIMITED);
    }

    @Test
    @DisplayName("Redis 장애면 세지 못할 뿐이라 통과시킨다(장애_처리_설계_가이드.md 캐시 기본 처리)")
    void Redis_장애면_통과시킨다() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> rateLimiter.checkLimit(1L)).doesNotThrowAnyException();
    }
}
