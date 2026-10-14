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
public class OwnerAccessTokenVersionRepository {
    private static final RedisScript<Long> ADVANCE_SCRIPT = loadScript();
    private final StringRedisTemplate redis;

    // 폐기 순번까지의 점주 AT를 차단하며, 늦은 요청이 차단 순번을 낮추지 못하게 한다.
    public void invalidateThroughVersion(Long ownerId, long version) {
        if (version < 0) {
            throw new IllegalArgumentException("폐기 순번은 음수일 수 없다");
        }
        Long result = redis.execute(ADVANCE_SCRIPT, List.of(key(ownerId)),
                String.format(Locale.ROOT, "%019d", version));
        if (result == null) {
            throw new DataAccessResourceFailureException("차단 순번 저장 결과가 없다");
        }
    }

    public boolean isValid(Long ownerId, long version) {
        if (version < 0) {
            return false;
        }
        String revokedThrough = redis.opsForValue().get(key(ownerId));
        return revokedThrough == null || version > Long.parseLong(revokedThrough);
    }

    // 차단 순번의 비교·저장을 한 번에 처리하는 점주 전용 Lua 파일을 읽는다.
    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/owner/access_token_advance_version.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private String key(Long ownerId) {
        return "accessTokenRevokedThroughVersion:OWNER:" + ownerId;
    }
}
