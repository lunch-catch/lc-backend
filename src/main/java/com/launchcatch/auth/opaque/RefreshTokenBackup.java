package com.launchcatch.auth.opaque;

import com.launchcatch.auth.Role;
import java.time.LocalDateTime;

/** Redis 장애 시에도 opaque Refresh Token을 검증·회전할 수 있게 남기는 DB 백업의 공통 모양이다. */
public record RefreshTokenBackup(Long subjectId, Role role, String tokenHash, LocalDateTime expiresAt) {
}
