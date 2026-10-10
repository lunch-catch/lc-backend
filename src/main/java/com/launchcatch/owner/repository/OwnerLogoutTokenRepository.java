package com.launchcatch.owner.repository;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OwnerLogoutTokenRepository {
    private static final RedisScript<Long> REVOKE_SCRIPT = loadScript();
    private final StringRedisTemplate redis;

    // DB에서 확정한 폐기 순번까지 차단하고 더 최신 로그인에서 저장한 RT는 유지한다.
    public void revokeThroughVersion(Long ownerId, String databaseHash, long version) {
        if (version <= 0) {
            throw new IllegalArgumentException("폐기 순번은 양수여야 한다");
        }
        Long result = redis.execute(REVOKE_SCRIPT,
                List.of("activeRefreshToken:OWNER:" + ownerId, "refreshTokenIssuanceVersion:OWNER:" + ownerId),
                String.format(Locale.ROOT, "%019d", version), databaseHash == null ? "" : databaseHash, "refreshToken:");
        if (result == null) {
            throw new DataAccessResourceFailureException("토큰 폐기 결과가 없다");
        }
    }

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/owner/refresh_token_revoke_through_version.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
