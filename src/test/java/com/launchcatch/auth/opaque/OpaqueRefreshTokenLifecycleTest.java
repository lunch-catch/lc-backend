package com.launchcatch.auth.opaque;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpaqueRefreshTokenLifecycleTest {
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    @Mock RefreshTokenBackupStore backupStore;

    @Test
    void 오래된_활성_포인터가_있어도_DB_해시로_폐기한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("old-hash"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of("new-hash"));
        when(backupStore.clearIfMatches(any(), any(), any())).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        lifecycle.revoke(Role.MEMBER, 1L, LocalDateTime.of(2026, 10, 7, 12, 0), Duration.ofMinutes(30));

        verify(refreshTokenRepository).revokeIfActiveHashMatches("new-hash", Role.MEMBER, 1L);
        verify(refreshTokenRepository).deleteActiveKeyIfMatches(Role.MEMBER, 1L, "old-hash");
        verify(accessTokenValidAfterRepository).invalidateBefore(any(), any(), any(), any());
    }
}
