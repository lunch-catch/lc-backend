package com.launchcatch.member.oauth;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class KakaoLoginStateRepository {

    private static final String KEY_PREFIX = "kakaoLoginState:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public void save(String state, String nonce) {
        redisTemplate.opsForValue().set(KEY_PREFIX + state, nonce, TTL);
    }

    public String consume(String state) {
        return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
    }
}
