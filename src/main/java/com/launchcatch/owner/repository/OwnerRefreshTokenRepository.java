package com.launchcatch.owner.repository;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.launchcatch.auth.opaque.TokenHasher;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * 점주 재발급의 처리 중 표시, 완료 확정, 실패 복구와 순번 기준 폐기를 담당한다.
 * 로그인과 호환되도록 공용 RefreshTokenRepository와 같은 Redis 키 및 값 형식을 사용한다.
 * DB 검증과 트랜잭션 순서는 점주 서비스가 결정한다.
 */
@Repository
@RequiredArgsConstructor
public class OwnerRefreshTokenRepository {
    private static final String KEY_PREFIX = "refreshToken:";
    private static final String PENDING_SUFFIX = "|PENDING";
    private static final String REVOKED_SUFFIX = "|REVOKED";
    private static final RedisScript<Long> CONFIRM_CONSUMED_SCRIPT = script("refresh_token_confirm_consumed.lua", Long.class);
    private static final RedisScript<String> CONSUME_SCRIPT = script("refresh_token_consume.lua", String.class);
    private static final RedisScript<Long> RESTORE_CONSUMED_SCRIPT = script("refresh_token_restore_consumed.lua", Long.class);
    private static final RedisScript<Long> REVOKE_THROUGH_VERSION_SCRIPT = script("refresh_token_revoke_through_version.lua", Long.class);

    private final StringRedisTemplate redisTemplate;

    /*
     * DB 커밋 전 기존 토큰을 원자적으로 소비한다.
     * 활성 포인터를 먼저 새 토큰으로 바꾸지 않아 동시 로그인 게시 순서를 보존한다.
     * 다른 역할의 토큰은 변경하지 않는다.
     */
    public ConsumeOutcome consumeForRotation(String refreshToken) {
        String value = redisTemplate.execute(CONSUME_SCRIPT,
                List.of(primaryKey(TokenHasher.sha256(refreshToken))), Role.OWNER.name());
        if (value == null) {
            return ConsumeOutcome.notFound();
        }
        if (value.endsWith(PENDING_SUFFIX)) {
            return ConsumeOutcome.inProgress(parse(value.substring(0, value.length() - PENDING_SUFFIX.length())));
        }
        if (value.endsWith(REVOKED_SUFFIX)) {
            return ConsumeOutcome.reuseDetected(parse(value.substring(0, value.length() - REVOKED_SUFFIX.length())));
        }
        return ConsumeOutcome.success(parse(value));
    }

    // DB 커밋과 게시 시도가 끝난 뒤에만 기존 RT를 확정된 재사용 감지 대상으로 바꾼다.
    public void confirmConsumed(String tokenHash) {
        Long confirmed = redisTemplate.execute(CONFIRM_CONSUMED_SCRIPT, List.of(primaryKey(tokenHash)));
        if (confirmed == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("소비 확정 결과가 없다");
        }
    }

    // DB가 기존 해시를 유지할 때 호출자가 행 잠금 아래 소비 표시를 복구한다.
    public void restoreConsumed(String tokenHash) {
        Long restored = redisTemplate.execute(RESTORE_CONSUMED_SCRIPT, List.of(primaryKey(tokenHash)));
        if (restored == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("소비 표시 복구 결과가 없다");
        }
    }

    /*
     * DB에서 RT를 폐기하고 확정한 순번을 받아 Redis의 대상 RT를 삭제한다.
     * 이 순번을 기록해 늦게 도착한 이전 발급 요청의 재저장을 막으며, 더 최신 로그인은 유지한다.
     * DB 해시가 없으면 databaseHash는 null이며, DB 폐기는 호출자가 먼저 처리해야 한다.
     */
    public void revokeThroughVersion(Long id, String databaseHash, long version) {
        if (version <= 0) {
            throw new IllegalArgumentException("폐기 순번은 양수여야 한다");
        }
        Long revoked = redisTemplate.execute(REVOKE_THROUGH_VERSION_SCRIPT,
                List.of(activeKey(id), "refreshTokenIssuanceVersion:OWNER:" + id),
                String.format(java.util.Locale.ROOT, "%019d", version),
                databaseHash == null ? "" : databaseHash, KEY_PREFIX);
        if (revoked == null) {
            throw new org.springframework.dao.DataAccessResourceFailureException("토큰 폐기 결과가 없다");
        }
    }

    // resources/scripts/owner의 Lua 파일을 읽고 Redis 실행 결과의 Java 타입을 지정한다.
    private static <T> RedisScript<T> script(String fileName, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/owner/" + fileName));
        script.setResultType(type);
        return script;
    }

    private String primaryKey(String hash) {
        return KEY_PREFIX + hash;
    }

    private String activeKey(Long id) {
        return "activeRefreshToken:OWNER:" + id;
    }

    private RefreshTokenData parse(String raw) {
        String[] parts = raw.split("\\|", 3);
        return new RefreshTokenData(Long.valueOf(parts[0]), Role.valueOf(parts[1]), Boolean.parseBoolean(parts[2]));
    }

    /** 토큰 교체 결과와 처리 중인 중복 요청을 구분한다. */
    public record ConsumeOutcome(Status status, RefreshTokenData data) {

        public enum Status { SUCCESS, NOT_FOUND, REUSE_DETECTED, IN_PROGRESS }

        public static ConsumeOutcome success(RefreshTokenData data) {
            return new ConsumeOutcome(Status.SUCCESS, data);
        }

        public static ConsumeOutcome inProgress(RefreshTokenData data) {
            return new ConsumeOutcome(Status.IN_PROGRESS, data);
        }

        public static ConsumeOutcome notFound() {
            return new ConsumeOutcome(Status.NOT_FOUND, null);
        }

        public static ConsumeOutcome reuseDetected(RefreshTokenData data) {
            return new ConsumeOutcome(Status.REUSE_DETECTED, data);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isReuseDetected() {
            return status == Status.REUSE_DETECTED;
        }
    }
}
