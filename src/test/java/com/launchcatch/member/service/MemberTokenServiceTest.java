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
        when(jwtTokenProvider.refreshTokenValidityMs(Role.MEMBER)).thenReturn(1_209_600_000L);
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
        verify(refreshTokenRepository).save(anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }
}
