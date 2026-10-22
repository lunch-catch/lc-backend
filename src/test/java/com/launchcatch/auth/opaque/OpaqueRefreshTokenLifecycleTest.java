package com.launchcatch.auth.opaque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    void Redis_장애면_DB_해시로_폴백해_회전하고_캐시를_채운다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(TokenHasher.sha256("old"), now))
                .thenReturn(Optional.of(backup(Role.MEMBER, now)));
        when(backupStore.rotateIfMatches(
                1L, TokenHasher.sha256("old"), TokenHasher.sha256("new"), now.plusDays(14), now))
                .thenReturn(true);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThat(fallbackReissue(lifecycle, now))
                .isEqualTo(new OpaqueRefreshTokenLifecycle.ReissueResult(1L, "new"));
        verify(refreshTokenRepository).save("new", 1L, Role.MEMBER, true, Duration.ofDays(14));
        verify(refreshTokenRepository).revokeIfActiveHashMatches(TokenHasher.sha256("old"), Role.MEMBER, 1L);
    }

    @Test
    void 폴백_뒤_캐시_동기화가_실패해도_재발급은_성공한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(backup(Role.MEMBER, now)));
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any())).thenReturn(true);
        doThrow(new QueryTimeoutException("redis down"))
                .when(refreshTokenRepository).save(any(), any(), any(), anyBoolean(), any());
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThat(fallbackReissue(lifecycle, now))
                .isEqualTo(new OpaqueRefreshTokenLifecycle.ReissueResult(1L, "new"));
    }

    @Test
    void 폴백에서_DB에_유효한_해시가_없으면_유효하지_않은_토큰이다() {
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.empty());
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> fallbackReissue(lifecycle, LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(backupStore, never()).rotateIfMatches(any(), any(), any(), any(), any());
    }

    @Test
    void 폴백의_실제_역할이_요청_역할과_다르면_거부한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(backup(Role.OWNER, now)));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> fallbackReissue(lifecycle, now))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(backupStore, never()).rotateIfMatches(any(), any(), any(), any(), any());
    }

    @Test
    void 폴백_CAS가_실패하면_이미_교체된_토큰이라_거부하고_캐시를_건드리지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(backup(Role.MEMBER, now)));
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any())).thenReturn(false);
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> fallbackReissue(lifecycle, now))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(refreshTokenRepository, never()).save(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void 폴백_조회가_실패하면_저장소_장애_오류로_변환한다() {
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenThrow(new QueryTimeoutException("db down"));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> fallbackReissue(lifecycle, LocalDateTime.now()))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
    }

    @Test
    void 폴백_DB_회전이_예외여도_새_해시가_확인되면_성공으로_처리한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(backup(Role.MEMBER, now)));
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of(TokenHasher.sha256("new")));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThat(fallbackReissue(lifecycle, now))
                .isEqualTo(new OpaqueRefreshTokenLifecycle.ReissueResult(1L, "new"));
    }

    @Test
    void 폴백_DB_회전이_예외이고_새_해시가_아니면_저장소_장애_오류로_변환한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
        stubRedisDown();
        when(backupStore.findValidByHash(any(), any())).thenReturn(Optional.of(backup(Role.MEMBER, now)));
        when(backupStore.rotateIfMatches(any(), any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));
        when(backupStore.findCurrentHash(1L)).thenReturn(Optional.of(TokenHasher.sha256("old")));
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> fallbackReissue(lifecycle, now))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
    }

    @Test
    void 캐시가_정상적으로_못_찾은_토큰에는_폴백하지_않는다() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.notFound());
        OpaqueRefreshTokenLifecycle lifecycle = lifecycle();

        assertThatThrownBy(() -> reissue(lifecycle))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class)
                .hasMessageContaining("다시 로그인");
        verify(backupStore, never()).findValidByHash(any(), any());
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

    private OpaqueRefreshTokenLifecycle.ReissueResult fallbackReissue(
            OpaqueRefreshTokenLifecycle lifecycle, LocalDateTime now) {
        return lifecycle.reissue("old", "new", Role.MEMBER, Duration.ofDays(14), Duration.ofMinutes(5), now);
    }

    private RefreshTokenBackup backup(Role role, LocalDateTime now) {
        return new RefreshTokenBackup(1L, role, TokenHasher.sha256("old"), now.plusDays(1));
    }

    private void stubRedisDown() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
    }

    private void stubSuccessfulRedisRotation() {
        when(refreshTokenRepository.compareAndRotate(any(), any(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
    }
}
