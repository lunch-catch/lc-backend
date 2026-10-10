package com.launchcatch.auth.jwt;

import com.launchcatch.auth.Role;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");
    private static final RedisScript<Long> ADVANCE_SCRIPT = loadAdvanceScript();

    private final StringRedisTemplate redisTemplate;

    /*
     * 지정한 시각 이전에 발급된 AT를 차단하도록 계정별 기준을 저장한다.
     * 동시에 요청되어도 차단 시각이 과거로 돌아가거나 남은 보관 시간이 줄지 않게 한다.
     */
    public void invalidateBefore(Role role, Long id, LocalDateTime cutoff, Duration ttl) {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("차단 기준의 TTL은 양수여야 한다");
        }
        Long advanced = redisTemplate.execute(ADVANCE_SCRIPT, List.of(key(role, id)),
                cutoff.format(FORMAT), String.valueOf(ttl.toMillis()));
        if (advanced == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("차단 기준 저장 결과가 없다");
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

    // Redis에서 차단 기준 시각의 비교·저장을 한 번에 처리하는 Lua 파일을 읽고, 실행 결과를 Long으로 받도록 설정한다.
    private static RedisScript<Long> loadAdvanceScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/access_token_advance_cutoff.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private String key(Role role, Long id) {
        return KEY_PREFIX + role.name() + ":" + id;
    }
}
