package com.launchcatch.auth.opaque;

import com.launchcatch.auth.Role;
import java.time.LocalDateTime;
import java.util.Optional;

/** 역할별 계정 테이블의 Refresh Token 해시 백업을 auth lifecycle에 제공하는 포트다. */
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
