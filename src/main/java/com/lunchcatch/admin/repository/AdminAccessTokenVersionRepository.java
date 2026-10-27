package com.lunchcatch.admin.repository;

import com.lunchcatch.auth.Role;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminAccessTokenVersionRepository {
    private final StringRedisTemplate redisTemplate;

    private static final String VERSION_PREFIX = "accessTokenRevokedVersion:";
    private static final DefaultRedisScript<Long> REVOKE_VERSION_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and (#current > #ARGV[1] or (#current == #ARGV[1] and current >= ARGV[1])) then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """, Long.class);


    // JWT 생성이 임의로 지연될 수 있으므로 관리자 폐기 순번에는 TTL을 두지 않는다.
    public void invalidateThroughVersion(Role role, Long id, long version) {
        if (version <= 0) { throw new IllegalArgumentException("폐기 순번은 양수여야 한다"); }
        Long result = redisTemplate.execute(REVOKE_VERSION_SCRIPT,
                List.of(VERSION_PREFIX + role.name() + ":" + id), Long.toString(version));
        if (result == null) {
            throw new DataAccessResourceFailureException("Access Token 폐기 순번 저장 결과가 없다");
        }
    }

    public boolean isVersionValid(Role role, Long id, long version) {
        if (version <= 0) { return false; }
        String stored = redisTemplate.opsForValue().get(VERSION_PREFIX + role.name() + ":" + id);
        return stored == null || version > Long.parseLong(stored);
    }


}
