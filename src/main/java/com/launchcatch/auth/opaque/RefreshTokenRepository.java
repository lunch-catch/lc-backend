package com.launchcatch.auth.opaque;

import com.launchcatch.auth.Role;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/*
 * Refresh Token 저장소. 인메모리 캐시만 다루고 업무 도메인을 전혀 모른다.
 * 캐시 장애 시 DataAccessException 을 그대로 던지며, 관계형 DB 백업과 폴백은 호출자 책임이다.
 *
 * 인증 정책은 SHA-256 해시를 관계형 DB 에 백업하고, 캐시 저장이 실패하면 DB 백업을 기준으로
 * 로그인을 유지하는 쪽이다. 백업 컬럼은 V1 스키마에 이미 있다. admin, owner, member 세
 * 테이블이 refresh_token_hash 와 refresh_token_expires_at 를 갖는다.
 *
 * 이 클래스는 캐시만 책임진다. 폴백은 각 역할의 토큰 서비스가 그 컬럼을 읽어
 * revokeIfActiveHashMatches 나 deleteByHash 를 부르는 모양으로 붙인다. 캐시와 DB 를 한
 * 클래스가 함께 다루면 캐시 장애 때 어느 쪽이 기준인지가 이 안에서 갈려 읽기 어려워진다.
 *
 * owner 에는 해시 인덱스가 없다. 폴백 조회를 붙일 때 admin 과 member 처럼
 * idx_owner_refresh_token_hash 를 더하는 마이그레이션이 필요하다.
 *
 * Opaque 토큰이라 키 설계가 둘이다. 토큰만 봐서는 누구 것인지 알 수 없으므로 조회와 회전은
 * "토큰 해시 -> 소유자 정보" 인 기본 레코드로 한다. 그런데 로그아웃과 재사용 의심 처리에서는
 * "이 계정의 현재 토큰을 찾아 지운다" 라는 반대 방향도 필요해서, 기본 레코드와 별개로
 * "역할:id -> 현재 토큰 해시" 를 가리키는 보조 인덱스를 하나 더 둔다. 보조 인덱스는 원자적
 * CAS 의 대상이 아니라 조회 편의를 위한 포인터일 뿐이고, 회전의 원자성은 기본 레코드에 대한
 * Lua 스크립트가 보장한다.
 *
 * 회전에 성공했을 때 옛 레코드를 곧바로 지우지 않고 tombstone 으로 남긴다. 죽은 토큰이 나중에
 * 재생되면 재사용 탐지는 되지만, 지워 버렸다면 그것이 누구 것이었는지 알 수 없어 그 계정의
 * 다른 Refresh Token 을 폐기하는 조치를 할 수 없다. 폐기된 Refresh 를 다시 쓰면 그 계정의
 * Refresh Token 을 모두 폐기해야 하므로 소유자 정보가 남아 있어야 한다. 그래서 compareAndRotate 의 결과가
 * Optional 하나가 아니라 SUCCESS, NOT_FOUND, REUSE_DETECTED 셋을 가르는 RotateOutcome 이다.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refreshToken:";
    private static final String ACTIVE_KEY_PREFIX = "activeRefreshToken:";
    private static final String FIELD_DELIMITER = "\\|";
    private static final String REVOKED_SUFFIX = "|REVOKED";

    private static final RedisScript<Long> SAVE_SCRIPT = loadSaveScript();
    private static final RedisScript<String> ROTATE_SCRIPT = loadRotateScript();
    private static final RedisScript<Long> REVOKE_SCRIPT = loadRevokeScript();
    private static final RedisScript<Long> DELETE_ACTIVE_KEY_IF_MATCHES_SCRIPT = loadDeleteActiveKeyIfMatchesScript();

    private final StringRedisTemplate redisTemplate;

    /** 로그인과 온보딩 발급 시 새 Refresh Token 을 저장한다. */
    public void save(String refreshToken, Long id, Role role, boolean remember, Duration ttl) {
        String hash = TokenHasher.sha256(refreshToken);
        redisTemplate.execute(
                SAVE_SCRIPT,
                List.of(primaryKey(hash), activeKey(role, id)),
                serialize(id, role, remember),
                hash,
                String.valueOf(ttl.toMillis())
        );
    }

    /** @return 저장된 값이 있으면 그 소유자 정보. 없거나 만료됐으면 empty. */
    public Optional<RefreshTokenData> find(String refreshToken) {
        String value = redisTemplate.opsForValue().get(primaryKey(TokenHasher.sha256(refreshToken)));
        return value == null ? Optional.empty() : Optional.of(parse(value));
    }

    /*
     * 원자적 회전. 옛 토큰 자리의 레코드를 새 토큰 자리로 옮기고 옛 자리는 tombstone 으로 남긴다.
     * 기본 레코드의 이동, tombstone 처리, 보조 인덱스 갱신을 하나의 Lua 실행으로 처리한다.
     */
    public RotateOutcome compareAndRotate(String oldRefreshToken, String newRefreshToken, Duration ttl) {
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);

        String value = redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(primaryKey(oldHash), primaryKey(newHash)),
                String.valueOf(ttl.toMillis()),
                ACTIVE_KEY_PREFIX,
                newHash
        );
        if (value == null) {
            return RotateOutcome.notFound();
        }
        if (value.endsWith(REVOKED_SUFFIX)) {
            RefreshTokenData data = parse(value.substring(0, value.length() - REVOKED_SUFFIX.length()));
            return RotateOutcome.reuseDetected(data);
        }

        RefreshTokenData data = parse(value);
        return RotateOutcome.success(data);
    }

    /*
     * 이 계정의 현재 Refresh Token 해시다. 보조 인덱스 기준이다.
     * 로그아웃과 탈퇴, 재사용 탐지 뒤 전체 폐기에서 "무엇을 지워야 하는지" 를 알아내는 데 쓴다.
     * 보조 인덱스가 캐시 축출이나 재시작으로 유실되면 empty 다. 그때는 호출부가 DB 백업으로 폴백한다.
     */
    public Optional<String> findActiveHash(Role role, Long id) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(activeKey(role, id)));
    }

    /*
     * 기본 레코드는 항상 지우고, 보조 인덱스는 지금도 이 해시를 가리킬 때만 함께 지운다.
     * 실패한 옛 폐기를 나중에 재시도하는 사이 새 로그인이나 재발급으로 보조 인덱스가 다른 해시를
     * 가리킬 수 있으므로, 두 삭제를 Lua 로 원자 처리해 새 Refresh Token 의 포인터를 지우지 않는다.
     */
    public void revokeIfActiveHashMatches(String tokenHash, Role role, Long id) {
        redisTemplate.execute(REVOKE_SCRIPT, List.of(primaryKey(tokenHash), activeKey(role, id)), tokenHash);
    }

    /** 삭제 타임아웃 뒤 실제 기본 레코드가 남았는지 후속 확인할 때 쓴다. */
    public boolean existsByHash(String tokenHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(primaryKey(tokenHash)));
    }

    /** 해시를 이미 알 때 그 레코드를 지운다. 보조 인덱스에서 구했든 DB 백업에서 구했든 같다. */
    public void deleteByHash(String tokenHash) {
        redisTemplate.delete(primaryKey(tokenHash));
    }

    /** 보조 인덱스 자체를 지운다. */
    public void deleteActiveKey(Role role, Long id) {
        redisTemplate.delete(activeKey(role, id));
    }

    /*
     * 보조 인덱스가 아직 expectedHash 를 가리키고 있을 때만 지운다.
     * 회전 뒤 DB 확정에 실패했을 때 보상 처리로 쓴다. 그 사이 다른 로그인이나 재발급으로
     * 더 최신 해시를 가리키게 됐다면 지우지 않는다.
     *
     * @return 실제로 지웠으면 true
     */
    public boolean deleteActiveKeyIfMatches(Role role, Long id, String expectedHash) {
        Long deleted = redisTemplate.execute(
                DELETE_ACTIVE_KEY_IF_MATCHES_SCRIPT, List.of(activeKey(role, id)), expectedHash);
        return deleted != null && deleted == 1L;
    }

    private String primaryKey(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }

    private String activeKey(Role role, Long id) {
        return ACTIVE_KEY_PREFIX + role.name() + ":" + id;
    }

    /*
     * 필드가 셋이다. 옮겨온 쪽은 역할과 트랙(TokenType)을 따로 실어 넷이었다.
     * 역할이 단일 축이 되면서 트랙 필드가 사라졌다.
     */
    private String serialize(Long id, Role role, boolean remember) {
        return id + "|" + role.name() + "|" + remember;
    }

    private RefreshTokenData parse(String raw) {
        String[] parts = raw.split(FIELD_DELIMITER, 3);
        return new RefreshTokenData(Long.valueOf(parts[0]), Role.valueOf(parts[1]), Boolean.parseBoolean(parts[2]));
    }

    public record RefreshTokenData(Long id, Role role, boolean remember) {
    }

    private static RedisScript<Long> loadSaveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_save.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<String> loadRotateScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_rotate.lua"));
        script.setResultType(String.class);
        return script;
    }

    private static RedisScript<Long> loadRevokeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_revoke.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<Long> loadDeleteActiveKeyIfMatchesScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
            local current = redis.call('GET', KEYS[1])

            if current == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end

            return 0
            """);
        script.setResultType(Long.class);
        return script;
    }

    /** 회전 결과 셋. Optional 하나로는 "없음" 과 "재사용 의심(소유자는 안다)" 을 가를 수 없다. */
    public record RotateOutcome(Status status, RefreshTokenData data) {

        public enum Status { SUCCESS, NOT_FOUND, REUSE_DETECTED }

        public static RotateOutcome success(RefreshTokenData data) {
            return new RotateOutcome(Status.SUCCESS, data);
        }

        public static RotateOutcome notFound() {
            return new RotateOutcome(Status.NOT_FOUND, null);
        }

        public static RotateOutcome reuseDetected(RefreshTokenData data) {
            return new RotateOutcome(Status.REUSE_DETECTED, data);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isReuseDetected() {
            return status == Status.REUSE_DETECTED;
        }
    }
}
