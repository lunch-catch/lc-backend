package com.launchcatch.auth.opaque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @Mock RefreshTokenBackupStore duplicateBackupStore;

    private OpaqueRefreshTokenLifecycle lifecycle() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        return new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));
    }

    @Test
    void Redis_장애면_DB_폴백_없이_실패한다() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> reissue(lifecycle))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
        verify(backupStore, never()).findCurrentHash(any());
    }

    @Test
    void DB가_옛_해시면_Redis_회전을_롤백하고_실패한다() {
        stubSuccessfulRedisRotation();
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any())).thenReturn(false);
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of(TokenHasher.sha256("old")));
        when(refreshTokenRepository.rollbackRotation(any(), any(), any(), any())).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> reissue(lifecycle))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
        verify(refreshTokenRepository).rollbackRotation(
                TokenHasher.sha256("old"), TokenHasher.sha256("new"), Role.MEMBER, 1L);
    }

    @Test
    void DB_예외_뒤_새_해시가_확인되면_회전_성공으로_처리한다() {
        stubSuccessfulRedisRotation();
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of(TokenHasher.sha256("new")));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThat(reissue(lifecycle)).isEqualTo(new OpaqueRefreshTokenLifecycle.ReissueResult(1L, "new"));
        verify(refreshTokenRepository, never()).rollbackRotation(any(), any(), any(), any());
    }

    @Test
    void DB_예외_뒤_옛_해시가_확인되면_Redis_회전을_롤백한다() {
        stubSuccessfulRedisRotation();
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of(TokenHasher.sha256("old")));
        when(refreshTokenRepository.rollbackRotation(any(), any(), any(), any())).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> reissue(lifecycle))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
        verify(refreshTokenRepository).rollbackRotation(
                TokenHasher.sha256("old"), TokenHasher.sha256("new"), Role.MEMBER, 1L);
    }

    @Test
    void 요청_역할과_Redis_역할이_다르면_회전을_롤백한다() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.OWNER, true)));
        when(refreshTokenRepository.rollbackRotation(any(), any(), any(), any())).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> reissue(lifecycle))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class);
        verify(refreshTokenRepository).rollbackRotation(
                TokenHasher.sha256("old"), TokenHasher.sha256("new"), Role.OWNER, 1L);
    }

    @Test
    void 재사용_탐지_폐기는_Access_Token_TTL을_사용한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);
        Duration accessTokenTtl = Duration.ofMinutes(5);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.reuseDetected(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("current"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of("current"));
        when(backupStore.clearIfMatches(1L, "current", now)).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofDays(14), accessTokenTtl, now))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class);
        verify(accessTokenValidAfterRepository).invalidateBefore(
                eq(Role.MEMBER), eq(1L), eq(now), eq(accessTokenTtl));
    }

    @Test
    void 오래된_활성_포인터가_있어도_DB_해시로_폐기한다() {
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("old-hash"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of("new-hash"));
        when(backupStore.clearIfMatches(any(), any(), any())).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        lifecycle.revoke(Role.MEMBER, 1L, LocalDateTime.now(), Duration.ofMinutes(5));

        verify(refreshTokenRepository).revokeIfActiveHashMatches("new-hash", Role.MEMBER, 1L);
        verify(refreshTokenRepository).deleteActiveKeyIfMatches(Role.MEMBER, 1L, "old-hash");
    }

    @Test
    void 같은_Role의_스토어가_둘이면_생성에_실패한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(duplicateBackupStore.role()).thenReturn(Role.MEMBER);
        assertThatThrownBy(() -> new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository,
                List.of(backupStore, duplicateBackupStore)))
                .isInstanceOf(IllegalStateException.class);
    }

    private OpaqueRefreshTokenLifecycle.ReissueResult reissue(OpaqueRefreshTokenLifecycle lifecycle) {
        return lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofDays(14), Duration.ofMinutes(5), LocalDateTime.now());
    }

    private void stubSuccessfulRedisRotation() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
    }
}
