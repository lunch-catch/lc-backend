package com.launchcatch.auth.jwt;

import com.launchcatch.auth.Role;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessResourceFailureException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/*
 * "이 시각 이전에 발급된 Access Token 은 전부 무효" 라는 계정 단위 커트라인을 인메모리 캐시에
 * 저장한다 (로그아웃 정책).
 *
 * JWT 는 자체 만료만 있고 서버 측 무효화 수단이 없다. 로그아웃이 Refresh 만 폐기하고 끝나면
 * 이미 나간 Access 가 최대 30분 더 산다. 그래서 로그아웃 시각을 적어 두고 그 전에 발급된
 * 토큰을 거부한다.
 *
 * 키는 RefreshTokenRepository 의 보조 인덱스와 같은 모양으로 맞춘다.
 */
@Repository
@RequiredArgsConstructor
public class AccessTokenValidAfterRepository {

    private static final String KEY_PREFIX = "accessTokenValidAfter:";

    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");
    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and current >= ARGV[1] then
                return 0
            end
            local ttl = math.max(tonumber(ARGV[2]), redis.call('PTTL', KEYS[1]))
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public void invalidateBefore(Role role, Long id, LocalDateTime cutoff, Duration ttl) {
        Long result = redisTemplate.execute(INVALIDATE_SCRIPT, List.of(key(role, id)),
                cutoff.format(CUTOFF_FORMAT), String.valueOf(ttl.toMillis()));
        if (result == null) {
            throw new DataAccessResourceFailureException("Access Token 폐기 기준 저장 결과가 없다");
        }
    }

    public boolean isValidAfter(Role role, Long id, LocalDateTime tokenIssuedAt) {
        String stored = redisTemplate.opsForValue().get(key(role, id));
        if (stored == null) {
            return true;
        }
        LocalDateTime cutoff = LocalDateTime.parse(stored);
        return !tokenIssuedAt.isBefore(cutoff);
    }

    private String key(Role role, Long id) {
        return KEY_PREFIX + role.name() + ":" + id;
    }
}
