package com.launchcatch.member.oauth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class KakaoLoginStateRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("state와 nonce를 5분 동안 저장한다")
    void state와_nonce를_저장한다() {
        KakaoLoginStateRepository repository = new KakaoLoginStateRepository(redisTemplate);
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.save("state", "nonce");

        verify(valueOperations).set(eq("kakaoLoginState:state"), eq("nonce"), eq(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("state를 원자적으로 소비한다")
    void state를_원자적으로_소비한다() {
        KakaoLoginStateRepository repository = new KakaoLoginStateRepository(redisTemplate);
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.consume("state");

        verify(valueOperations).getAndDelete("kakaoLoginState:state");
    }
}
