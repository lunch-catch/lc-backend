package com.launchcatch.auth.opaque;

import com.launchcatch.auth.Role;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 역할별 계정 테이블의 Refresh Token 해시 백업을 auth lifecycle에 제공하는 포트다.
 *
 * <p>유효 상태 판정은 구현 책임이며, 해당 역할의 로그인 가능 상태 집합을 따라야 한다.
 * {@link #findValidByHash}와 {@link #rotateIfMatches}는 로그인 가능한 계정만 허용한다.
 * {@link #save}는 토큰을 가질 수 없는 상태거나 대상이 없으면 예외 대신 {@code false}를 반환한다.
 * {@link #clearIfMatches}는 어떤 상태에서도 토큰을 폐기할 수 있도록 상태로 제한하지 않는다.
 *
 * <p>Redis 장애 시 {@link #findValidByHash}가 DB 폴백 조회 경로가 되므로 구현 테이블은
 * Refresh Token 해시 컬럼에 인덱스를 가져야 한다.
 */
public interface RefreshTokenBackupStore {

    Role role();

    Optional<RefreshTokenBackup> findValidByHash(String tokenHash, LocalDateTime now);

    Optional<String> findCurrentHash(Long subjectId);

    boolean save(Long subjectId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now);

    boolean rotateIfMatches(
            Long subjectId,
            String oldTokenHash,
            String newTokenHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now);

    boolean clearIfMatches(Long subjectId, String tokenHash, LocalDateTime now);
}
