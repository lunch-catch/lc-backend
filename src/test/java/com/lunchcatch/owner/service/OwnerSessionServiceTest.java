package com.lunchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.TokenHasher;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.entity.OwnerSession;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.owner.repository.OwnerSessionRepository;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerSessionServiceTest {
    private final OwnerRepository owners = mock(OwnerRepository.class);
    private final OwnerSessionRepository sessions = mock(OwnerSessionRepository.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = mock(Clock.class);
    private final com.lunchcatch.owner.repository.OwnerSessionTokenRepository tokens = mock(com.lunchcatch.owner.repository.OwnerSessionTokenRepository.class);
    private final Instant instant = Instant.parse("2026-10-24T03:00:00Z");
    private final LocalDateTime now = LocalDateTime.ofInstant(instant, ClockConfig.ZONE);
    private Owner owner;
    private OwnerSession session;
    private OwnerSessionService service;

    @BeforeEach
    void setUp() {
        owner = Owner.create("owner@example.com", "password-hash");
        ReflectionTestUtils.setField(owner, "id", 7L);
        session = OwnerSession.create(7L, 0, TokenHasher.sha256("old"), now.plusDays(1), now);
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(owners.findById(7L)).thenReturn(Optional.of(owner));
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(sessions.findCurrent(session.getSessionKey(), 7L)).thenReturn(Optional.of(session));
        when(sessions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(clock.instant()).thenReturn(instant);
        when(jwt.refreshTokenValidityMs(Role.OWNER)).thenReturn(Duration.ofDays(1).toMillis());
        when(jwt.createAccessToken(eq(7L), eq(Role.OWNER), anyMap())).thenReturn("access");
        service = new OwnerSessionService(owners, sessions, jwt, clock, manager, tokens);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 로그인은_독립된_세션과_상태별_응답을_만든다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        var first = service.issue(owner);
        var second = service.issue(owner);
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(first.response().status()).isEqualTo(status);
        assertThat(owner.getLastLoginAt()).isEqualTo(now);
        assertThat(owner.getRefreshTokenHash()).isNull();
        var capture = org.mockito.ArgumentCaptor.forClass(OwnerSession.class);
        verify(sessions, times(2)).saveAndFlush(capture.capture());
        assertThat(capture.getAllValues()).extracting(OwnerSession::getSessionKey).doesNotHaveDuplicates();
        assertThat(capture.getAllValues().getFirst().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(first.refreshToken()));
    }

    @Test
    void 세션_RT를_회전하고_다른_세션과_계정_폐기_세대는_유지한다() {
        var result = service.refreshIfPresent("old").orElseThrow();
        assertThat(result.refreshToken()).isNotBlank().isNotEqualTo("old");
        assertThat(session.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        assertThat(session.getPreviousTokenHash()).isEqualTo(TokenHasher.sha256("old"));
        assertThat(owner.getSessionGeneration()).isZero();
        assertThat(result.toString()).doesNotContain("access", result.refreshToken());
    }

    @Test
    void 유예_중_이전_토큰과_현재_토큰은_성공하지만_RT를_다시_회전하지_않는다() {
        var winner = service.refreshIfPresent("old").orElseThrow();
        assertThat(service.refreshIfPresent("old").orElseThrow().refreshToken()).isNull();
        assertThat(service.refreshIfPresent(winner.refreshToken()).orElseThrow().refreshToken()).isNull();
        assertThat(session.isActive(0, now)).isTrue();
    }

    @Test
    void 유예가_끝난_이전_RT의_재사용은_그_세션만_폐기한다() {
        service.refreshIfPresent("old");
        when(clock.instant()).thenReturn(instant.plusSeconds(10));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-004");
        assertThat(session.getRevokedAt()).isEqualTo(now.plusSeconds(10));
        verify(manager, atLeast(2)).commit(any());
        assertThat(owner.getSessionGeneration()).isZero();
    }

    @Test
    void 없는_토큰은_기존_단일_토큰_호환_경로로_넘긴다() {
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.refreshIfPresent("legacy")).isEmpty();
        assertThat(service.refreshIfPresent(null)).isEmpty();
        assertThat(service.refreshIfPresent(" ")).isEmpty();
    }

    @Test
    void 잠금_대기_중_두번_교체된_토큰은_현재_세션을_폐기하지_않는다() {
        session.rotate(TokenHasher.sha256("second"), now.plusDays(1), now, now.plusSeconds(10));
        session.rotate(TokenHasher.sha256("third"), now.plusDays(1), now, now.plusSeconds(10));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertThat(session.getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지와_탈퇴는_갱신을_막고_AT의_상태별_인가는_기존_서비스에_맡긴다(OwnerStatus status) {
        ReflectionTestUtils.setField(owner, "status", status);
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isTrue();
    }

    @Test
    void 전체_폐기는_기존_세대의_세션을_모두_막는다() {
        owner.clearRefreshToken();
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
    }

    @Test
    void 로그아웃은_현재_세션의_AT와_RT만_막는다() {
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isTrue();
        service.logout(7L, session.getSessionKey());
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertThat(owner.getSessionGeneration()).isZero();
    }

    @Test
    void 만료된_세션은_갱신하거나_인증할_수_없다() {
        when(clock.instant()).thenReturn(instant.plus(Duration.ofDays(1)));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
    }

    @Test
    void 없는_계정과_없는_세션으로_갱신하지_못한다() {
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.empty());
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertError(() -> service.logout(7L, session.getSessionKey()), "AUTH-005");
        when(owners.findByIdForLogin(7L)).thenReturn(Optional.of(owner));
        when(sessions.findCurrent(anyString(), eq(7L))).thenReturn(Optional.empty());
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
        assertError(() -> service.logout(7L, session.getSessionKey()), "AUTH-005");
    }

    @Test
    void 잘못된_클레임과_다른_점주의_세션은_인증하지_않는다() {
        assertThat(service.isValid(7L, null, com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
        assertThat(service.isValid(7L, 1, com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
        assertThat(service.isValid(7L, "invalid", com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
        when(sessions.findCurrent(session.getSessionKey(), 7L)).thenReturn(Optional.empty());
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
        when(owners.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
    }

    @Test
    void 갱신_조회와_저장_실패는_503이다() {
        when(sessions.findByTokenHash(anyString())).thenThrow(new DataAccessResourceFailureException("DB"));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-002");
        doReturn(Optional.of(session)).when(sessions).findByTokenHash(anyString());
        when(sessions.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("DB"));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-002");
        assertError(() -> service.logout(7L, session.getSessionKey()), "AUTH-002");
    }

    @Test
    void 갱신과_로그아웃의_커밋_실패도_503이다() {
        doThrow(new UnexpectedRollbackException("commit")).when(manager).commit(any());
        assertError(() -> service.refreshIfPresent("old"), "AUTH-002");
        assertError(() -> service.logout(7L, session.getSessionKey()), "AUTH-002");
    }

    private void assertError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo(code));
    }

    @Test
    void 로그인_커밋_후_세션별_순번과_남은_TTL로_Redis에_게시한다() {
        var result = new OwnerLoginResult(new com.lunchcatch.owner.dto.OwnerLoginResponse(
                owner.getEmail(), Role.OWNER, OwnerStatus.ONBOARDING, null), "access", "old");
        service.publishIssued(result);
        verify(tokens).publish("old", 7L, session.getSessionKey(), 1L, Duration.ofDays(1));
    }

    @Test
    void Redis_게시_실패는_DB에_확정한_발급을_취소하지_않는다() {
        doThrow(new DataAccessResourceFailureException("Redis")).when(tokens).publish(anyString(), anyLong(), anyString(), anyLong(), any());
        var result = new OwnerLoginResult(new com.lunchcatch.owner.dto.OwnerLoginResponse(
                owner.getEmail(), Role.OWNER, OwnerStatus.ONBOARDING, null), "access", "old");
        assertThatCode(() -> service.publishIssued(result)).doesNotThrowAnyException();
    }

    @Test
    void 게시_전_DB_확인_실패는_503이다() {
        when(sessions.findByTokenHash(anyString())).thenThrow(new DataAccessResourceFailureException("DB"));
        var result = new OwnerLoginResult(null, "access", "old");
        assertError(() -> service.publishIssued(result), "AUTH-002");
    }

    @Test
    void 폐기된_세션은_지연_게시하지_않는다() {
        session.revoke(now);
        service.publishIssued(new OwnerLoginResult(null, "access", "old"));
        verify(tokens, never()).publish(anyString(), anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void Redis_소비_장애는_DB_해시를_검증한_뒤_복구한다() {
        when(tokens.consume("old")).thenThrow(new DataAccessResourceFailureException("Redis"));
        var result = service.refreshIfPresent("old").orElseThrow();
        assertThat(result.refreshToken()).isNotBlank();
        verify(tokens).publish(eq(result.refreshToken()), eq(7L), eq(session.getSessionKey()), eq(2L), any());
    }

    @Test
    void Redis_세션_정보를_찾아도_DB의_현재_해시를_검증한다() {
        when(tokens.consume("old")).thenReturn(Optional.of(new com.lunchcatch.owner.repository.OwnerSessionTokenRepository.TokenData(7L, session.getSessionKey(), false)));
        when(sessions.findBySessionKeyAndOwnerId(session.getSessionKey(), 7L)).thenReturn(Optional.of(session));
        assertThat(service.refreshIfPresent("old").orElseThrow().refreshToken()).isNotBlank();
    }

    @Test
    void Redis에서_이미_폐기한_현재_해시는_DB로_되살리지_않는다() {
        when(tokens.consume("old")).thenReturn(Optional.of(new com.lunchcatch.owner.repository.OwnerSessionTokenRepository.TokenData(7L, session.getSessionKey(), true)));
        when(sessions.findBySessionKeyAndOwnerId(session.getSessionKey(), 7L)).thenReturn(Optional.of(session));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-003");
    }

    @Test
    void Redis_AT_조회_장애는_DB_검증된_LENIENT만_허용한다() {
        when(tokens.isRevoked(session.getSessionKey())).thenThrow(new DataAccessResourceFailureException("Redis"));
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isTrue();
        assertThatThrownBy(() -> service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void Redis_소비_확정_실패여도_DB_회전은_유지한다() {
        doThrow(new DataAccessResourceFailureException("Redis")).when(tokens).confirm(anyString());
        assertThat(service.refreshIfPresent("old").orElseThrow().refreshToken()).isNotBlank();
    }

    @Test
    void 로그아웃_Redis_폐기_실패는_503이지만_DB_폐기는_유지한다() {
        doThrow(new DataAccessResourceFailureException("Redis")).when(tokens).revoke(anyString(), anyString(), anyLong(), any());
        assertError(() -> service.logout(7L, session.getSessionKey()), "AUTH-002");
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(service.isValid(7L, session.getSessionKey(), com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
    }

    @Test
    void DB가_기존_RT를_유지하면_소비표시_복구를_시도한다() {
        doThrow(new UnexpectedRollbackException("DB")).when(manager).commit(any());
        // 회전 전 DB 잠금 실패를 만들어 아직 교체되지 않은 RT만 복구하는 분기를 확인한다.
        when(owners.findByIdForLogin(7L)).thenThrow(new DataAccessResourceFailureException("DB"))
                .thenReturn(Optional.of(owner));
        assertError(() -> service.refreshIfPresent("old"), "AUTH-002");
        verify(tokens).restore(TokenHasher.sha256("old"));
    }

}
