package com.launchcatch.owner.repository;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * 점주 상태의 원본은 DB이며 Redis는 조회 속도를 위한 캐시다.
 * TTL은 owner.status-cache.ttl-seconds로 설정하며 기본값은 30초다.
 * Redis 장애 시 DB로 대체 조회할지는 호출하는 서비스에서 결정한다.
 */
@Repository
public class OwnerStatusCacheRepository {
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public OwnerStatusCacheRepository(StringRedisTemplate redis,
            @Value("${owner.status-cache.ttl-seconds:30}") long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("점주 상태 캐시 TTL은 양수여야 합니다.");
        }
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Optional<String> find(Long ownerId) {
        return Optional.ofNullable(redis.opsForValue().get(key(ownerId)));
    }

    public void save(Long ownerId, String status) {
        redis.opsForValue().set(key(ownerId), status, ttl);
    }

    public void delete(Long ownerId) {
        redis.delete(key(ownerId));
    }

    private String key(Long ownerId) {
        return "ownerStatus:" + ownerId;
    }
}
