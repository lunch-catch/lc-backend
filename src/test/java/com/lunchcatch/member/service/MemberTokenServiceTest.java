package com.lunchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.OpaqueRefreshTokenLifecycle;
import com.lunchcatch.member.client.KakaoLogoutClient;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.repository.MemberRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class MemberTokenServiceTest {
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock OpaqueRefreshTokenLifecycle refreshTokenLifecycle;
    @Mock MemberRepository memberRepository;
    @Mock KakaoLogoutClient kakaoLogoutClient;
    @Mock Member member;
    @Mock PlatformTransactionManager transactionManager;

    private MemberTokenService service() {
        return new MemberTokenService(jwtTokenProvider, refreshTokenLifecycle, memberRepository, kakaoLogoutClient,
                new TransactionTemplate(transactionManager),
                Clock.fixed(Instant.parse("2026-10-06T04:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 발급은_공통_lifecycle에_위임한다() {
        when(member.getId()).thenReturn(1L);
        when(jwtTokenProvider.createAccessToken(1L, Role.MEMBER)).thenReturn("access");
        when(jwtTokenProvider.refreshTokenValidityMs(Role.MEMBER)).thenReturn(600_000L);
        MemberTokenService.TokenPair result = service().issue(member);
        assertThat(result.accessToken()).isEqualTo("access");
        verify(refreshTokenLifecycle).issue(any(), any(), any(), any(), any());
    }

    @Test
    void 재발급은_공통_lifecycle의_소유자로_accessToken을_발급한다() {
        when(jwtTokenProvider.refreshTokenValidityMs(Role.MEMBER)).thenReturn(600_000L);
        when(jwtTokenProvider.getAccessTokenValidityMs()).thenReturn(30_000L);
        when(refreshTokenLifecycle.reissue(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OpaqueRefreshTokenLifecycle.ReissueResult(1L, "new"));
        when(jwtTokenProvider.createAccessToken(1L, Role.MEMBER)).thenReturn("access");
        assertThat(service().reissue("old")).isEqualTo(new MemberTokenService.TokenPair("access", "new", 1L));
    }

    @Test
    void 로그아웃은_공통_lifecycle로_폐기한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getProviderUserId()).thenReturn(null);
        when(jwtTokenProvider.getAccessTokenValidityMs()).thenReturn(30_000L);
        service().logout(1L);
        verify(refreshTokenLifecycle).revoke(any(), any(), any(), any());
    }

    // 커넥션을 얻지 못하는 실패는 트랜잭션을 여는 순간에 난다. 그 자리도 명세대로 503 AUTH-002 다(#1320)
    @Test
    void 로그아웃이_트랜잭션을_열지_못하면_AUTH002다() {
        when(transactionManager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("pool exhausted"));

        assertThatThrownBy(() -> service().logout(1L))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE));
    }

    @Test
    void 로그아웃의_SQL_오류는_AUTH002로_바꾸지_않는다() {
        BadSqlGrammarException bug = new BadSqlGrammarException("find", "SELECT", new SQLException("syntax"));
        when(memberRepository.findById(1L)).thenThrow(bug);

        assertThatThrownBy(() -> service().logout(1L)).isSameAs(bug);
    }

    @Test
    void 로그아웃_커밋_뒤_카카오_세션을_종료한다() {
        when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
        when(member.getProviderUserId()).thenReturn("kakao-1");
        when(jwtTokenProvider.getAccessTokenValidityMs()).thenReturn(30_000L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().logout(1L);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(kakaoLogoutClient).logout("kakao-1");
    }
}
