package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.member.client.KakaoLogoutClient;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class MemberTokenServiceTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private KakaoLogoutClient kakaoLogoutClient;
    @Mock
    private Member member;

    private MemberTokenService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T04:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new MemberTokenService(
                jwtTokenProvider,
                refreshTokenRepository,
                accessTokenValidAfterRepository,
                memberRepository,
                kakaoLogoutClient,
                clock);
        org.mockito.Mockito.lenient()
                .when(jwtTokenProvider.refreshTokenValidityMs(Role.MEMBER))
                .thenReturn(1_209_600_000L);
    }

    @Test
    @DisplayName("DB Refresh Token 백업이 실패하면 로그인 토큰 발급을 거부한다")
    void DB_백업_실패면_발급을_거부한다() {
        when(member.getId()).thenReturn(1L);
        when(memberRepository.updateRefreshTokenBackup(anyLong(), anyString(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.issue(member))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");
    }

    @Test
    @DisplayName("캐시 저장이 실패해도 DB 백업이 있으면 회원 토큰을 발급한다")
    void 캐시_저장_실패는_발급을_막지_않는다() {
        when(member.getId()).thenReturn(1L);
        when(jwtTokenProvider.createAccessToken(1L, Role.MEMBER)).thenReturn("access");
        when(memberRepository.updateRefreshTokenBackup(anyLong(), anyString(), any(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("redis down"))
                .when(refreshTokenRepository).save(anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());

        MemberTokenService.TokenPair result = service.issue(member);

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("정상 캐시에 없는 Refresh Token은 DB 폴백 없이 거부한다")
    void 정상_캐시의_없는_토큰은_거부한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.notFound());

        assertThatThrownBy(() -> service.reissue("old"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("다시 로그인");
    }

    @Test
    @DisplayName("캐시 장애면 DB 해시 CAS로 Refresh Token을 회전한다")
    void 캐시_장애면_DB_폴백으로_회전한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(member.getRefreshTokenExpiresAt()).thenReturn(java.time.LocalDateTime.of(2026, 10, 21, 13, 0));
        when(member.getId()).thenReturn(1L);
        when(memberRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.of(member));
        when(memberRepository.rotateRefreshTokenBackupIfMatches(
                anyLong(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jwtTokenProvider.createAccessToken(1L, Role.MEMBER)).thenReturn("access");

        MemberTokenService.TokenPair result = service.reissue("old");

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.memberId()).isEqualTo(1L);
        verify(refreshTokenRepository).save(anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("캐시 Refresh Token을 정상적으로 회전하면 DB 백업도 함께 바꾼다")
    void 캐시_토큰을_정상_회전한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(memberRepository.rotateRefreshTokenBackupIfMatches(
                anyLong(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        when(jwtTokenProvider.createAccessToken(1L, Role.MEMBER)).thenReturn("access");

        MemberTokenService.TokenPair result = service.reissue("old");

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.memberId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("캐시 회전 후 DB 백업 갱신이 실패하면 새 캐시 토큰을 철회한다")
    void 캐시_회전_후_DB_백업_갱신_실패시_새_캐시_토큰을_철회한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(memberRepository.rotateRefreshTokenBackupIfMatches(
                anyLong(), anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reissue("old"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("일시적으로 처리할 수 없습니다");

        verify(refreshTokenRepository).revokeIfActiveHashMatches(anyString(), org.mockito.ArgumentMatchers.eq(Role.MEMBER), org.mockito.ArgumentMatchers.eq(1L));
    }

    @Test
    @DisplayName("캐시 토큰의 회원을 찾지 못하면 재발급을 거부한다")
    void 캐시_토큰의_회원을_찾지_못하면_재발급을_거부한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.success(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(memberRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissue("old"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("다시 로그인");
    }

    @Test
    @DisplayName("캐시 장애 뒤 DB 백업에서 토큰을 찾지 못하면 재발급을 거부한다")
    void 캐시_장애_뒤_DB_백업에서_토큰을_찾지_못하면_재발급을_거부한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenThrow(new QueryTimeoutException("redis down"));
        when(memberRepository.findByRefreshTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissue("old"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("다시 로그인");
    }

    @Test
    @DisplayName("재사용된 Refresh Token은 현재 토큰과 Access Token을 함께 철회한다")
    void 재사용된_토큰은_모든_회원_토큰을_철회한다() {
        when(refreshTokenRepository.compareAndRotate(anyString(), anyString(), any()))
                .thenReturn(RefreshTokenRepository.RotateOutcome.reuseDetected(
                        new RefreshTokenRepository.RefreshTokenData(1L, Role.MEMBER, true)));
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("current-hash"));
        when(memberRepository.clearRefreshTokenBackupIfHashMatches(anyLong(), anyString(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.reissue("reused"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("모든 기기에서 로그아웃");

        verify(refreshTokenRepository).revokeIfActiveHashMatches("current-hash", Role.MEMBER, 1L);
        verify(accessTokenValidAfterRepository).invalidateBefore(
                org.mockito.ArgumentMatchers.eq(Role.MEMBER),
                org.mockito.ArgumentMatchers.eq(1L),
                any(),
                any());
    }

    @Test
    @DisplayName("로그아웃 커밋 뒤에는 카카오 로그아웃을 요청한다")
    void 로그아웃_커밋_뒤에는_카카오_로그아웃을_요청한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getProviderUserId()).thenReturn("kakao-1");
        when(refreshTokenRepository.findActiveHash(Role.MEMBER, 1L)).thenReturn(Optional.of("current-hash"));
        when(memberRepository.clearRefreshTokenBackupIfHashMatches(anyLong(), anyString(), any())).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.logout(1L);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(kakaoLogoutClient).logout("kakao-1");
    }
}
