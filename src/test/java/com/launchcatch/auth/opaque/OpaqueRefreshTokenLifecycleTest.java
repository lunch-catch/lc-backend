package com.launchcatch.auth.opaque;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofMinutes(30), Duration.ofMinutes(5), LocalDateTime.now()))
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

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofMinutes(30), Duration.ofMinutes(5), LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
    }

    @Test
    void 요청_역할과_Redis_토큰_역할이_다르면_회전된_토큰을_보상_폐기한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.OWNER, true)));
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofMinutes(30), Duration.ofMinutes(5), LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(refreshTokenRepository).revokeIfActiveHashMatches(
                TokenHasher.sha256("new"), Role.OWNER, 1L);
    }

    @Test
    void DB_폴백의_실제_역할이_요청_역할과_다르면_거부한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(
                new RefreshTokenBackup(1L, Role.OWNER, TokenHasher.sha256("old"), LocalDateTime.now().plusDays(1))));
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofMinutes(30), Duration.ofMinutes(5), LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(backupStore, never()).rotateIfMatches(any(), any(), any(), any(), any());
    }

    @Test
    void 재사용_탐지_폐기는_Access_Token_TTL을_사용한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 12, 0);
        Duration accessTokenTtl = Duration.ofMinutes(5);
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.reuseDetected(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("current"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of("current"));
        when(backupStore.clearIfMatches(1L, "current", now)).thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository, accessTokenValidAfterRepository, List.of(backupStore));

        assertThatThrownBy(() -> lifecycle.reissue(
                "old", "new", Role.MEMBER, Duration.ofDays(14), accessTokenTtl, now))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("모든 기기에서 로그아웃");
        verify(accessTokenValidAfterRepository).invalidateBefore(
                eq(Role.MEMBER), eq(1L), eq(now), eq(accessTokenTtl));
    }

    @Test
    void 같은_Role의_DB_백업_스토어가_둘이면_생성에_실패한다() {
        when(backupStore.role()).thenReturn(Role.MEMBER);
        when(duplicateBackupStore.role()).thenReturn(Role.MEMBER);

        assertThatThrownBy(() -> new OpaqueRefreshTokenLifecycle(
                refreshTokenRepository,
                accessTokenValidAfterRepository,
                List.of(backupStore, duplicateBackupStore)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEMBER");
    }
}
