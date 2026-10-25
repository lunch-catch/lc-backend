package com.launchcatch.owner.repository;

import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OwnerSessionTokenRepository {
    private static final String TOKEN_PREFIX = "ownerSessionToken:";
    private static final String ACTIVE_PREFIX = "ownerSessionActive:";
    private static final String VERSION_PREFIX = "ownerSessionVersion:";
    private static final String REVOKED_PREFIX = "ownerSessionRevoked:";
    private static final RedisScript<String> CONSUME = script("owner/refresh_token_consume.lua", String.class);
    private static final RedisScript<Long> CONFIRM = script("owner/refresh_token_confirm_consumed.lua", Long.class);
    private static final RedisScript<Long> RESTORE = script("owner/refresh_token_restore_consumed.lua", Long.class);
    private static final RedisScript<Long> SAVE = script("owner/session_save_if_newer.lua", Long.class);
    private static final RedisScript<Long> REVOKE = script("owner/session_revoke.lua", Long.class);
    private final StringRedisTemplate redis;

    public Optional<TokenData> consume(String token) {
        String value = redis.execute(CONSUME, List.of(TOKEN_PREFIX + TokenHasher.sha256(token)), "OWNER");
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length < 4 || parts.length > 5 || !"OWNER".equals(parts[1])) {
            throw new DataAccessResourceFailureException("점주 세션 캐시 형식이 올바르지 않습니다.");
        }
        try {
            return Optional.of(new TokenData(Long.valueOf(parts[0]), parts[3],
                    parts.length == 5 && "REVOKED".equals(parts[4])));
        } catch (NumberFormatException e) {
            throw new DataAccessResourceFailureException("점주 세션 캐시 식별자가 올바르지 않습니다.");
        }
    }

    public void publish(String token, Long ownerId, String key, long version, Duration ttl) {
        if (version <= 0 || ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("게시 순번과 TTL은 양수여야 합니다.");
        }
        String hash = TokenHasher.sha256(token);
        requireResult(redis.execute(SAVE, List.of(TOKEN_PREFIX + hash, ACTIVE_PREFIX + key, VERSION_PREFIX + key),
                ownerId + "|OWNER|true|" + key, hash, Long.toString(ttl.toMillis()), padded(version)));
    }

    public void confirm(String hash) {
        requireResult(redis.execute(CONFIRM, List.of(TOKEN_PREFIX + hash)));
    }

    public void restore(String hash) {
        requireResult(redis.execute(RESTORE, List.of(TOKEN_PREFIX + hash)));
    }

    public void revoke(String key, String hash, long version, Duration ttl) {
        if (version <= 0 || ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("폐기 순번과 TTL은 양수여야 합니다.");
        }
        requireResult(redis.execute(REVOKE, List.of(ACTIVE_PREFIX + key, VERSION_PREFIX + key, REVOKED_PREFIX + key),
                padded(version), hash, TOKEN_PREFIX, Long.toString(ttl.toMillis())));
    }

    public boolean isRevoked(String key) {
        return Boolean.TRUE.equals(redis.hasKey(REVOKED_PREFIX + key));
    }

    private static String padded(long version) {
        return String.format(Locale.ROOT, "%019d", version);
    }

    private static void requireResult(Long result) {
        if (result == null) {
            throw new DataAccessResourceFailureException("점주 세션 캐시 작업 결과가 없습니다.");
        }
    }

    private static <T> RedisScript<T> script(String file, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("scripts/" + file));
        script.setResultType(type);
        return script;
    }

    public record TokenData(Long ownerId, String sessionKey, boolean revoked) { }
}
