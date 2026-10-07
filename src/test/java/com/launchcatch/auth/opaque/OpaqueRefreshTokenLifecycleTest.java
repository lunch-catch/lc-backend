package com.launchcatch.auth.opaque;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.QueryTimeoutException;

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

    @Test
    void Redis_회전_뒤_DB_회전이_실패하면_새_Redis_토큰을_보상_폐기한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        assertThatThrownBy(() -> lifecycle.reissue("old", "new", Role.MEMBER, Duration.ofMinutes(30), LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
        verify(refreshTokenRepository).revokeIfActiveHashMatches(TokenHasher.sha256("new"), Role.MEMBER, 1L);
    }

    @Test
    void DB_폴백_조회가_실패해도_저장소_장애_오류로_변환한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(backupStore.findValidByHash(any(), any())).thenThrow(new QueryTimeoutException("db down"));
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        assertThatThrownBy(() -> lifecycle.reissue("old", "new", Role.MEMBER, Duration.ofMinutes(30), LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
    }
}
