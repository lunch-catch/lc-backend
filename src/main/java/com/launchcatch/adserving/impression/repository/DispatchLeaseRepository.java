package com.launchcatch.adserving.impression.repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 노출 릴레이를 배치 서버 한 대만 돌리게 하는 점유다(#1010). 정합성이 아니라 일을 줄이는 장치다.
 * 점유가 만료돼 두 서버가 같은 노출을 넘겨도 정산이 serveId 로 멱등이라 과금은 한 번이다.
 * 서버 id 는 프로세스마다 새로 만든다. 같은 서버가 다시 떠도 앞 프로세스의 점유를 이어받지 않고 만료를 기다린다.
 */
@Repository
public class DispatchLeaseRepository {

    static final String KEY = "adserving:impression-dispatch:lease";

    private static final RedisScript<Long> RENEW_SCRIPT = script("scripts/impression_dispatch_lease_renew.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT = script("scripts/impression_dispatch_lease_release.lua");

    private final StringRedisTemplate redisTemplate;
    private final String owner = UUID.randomUUID().toString();

    public DispatchLeaseRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 아무도 점유하지 않았을 때만 내 점유로 적는다.
    public boolean acquire(Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(KEY, owner, ttl));
    }

    // 내 점유일 때만 만료를 늘린다. 다른 서버가 가져갔으면 false 다.
    public boolean renew(Duration ttl) {
        Long renewed = redisTemplate.execute(RENEW_SCRIPT, List.of(KEY), owner, String.valueOf(ttl.toMillis()));
        return renewed != null && renewed == 1L;
    }

    public void release() {
        redisTemplate.execute(RELEASE_SCRIPT, List.of(KEY), owner);
    }

    private static RedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
