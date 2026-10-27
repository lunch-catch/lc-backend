package com.lunchcatch.owner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.owner.dto.OwnerLoginRequest;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.entity.OwnerSession;
import com.lunchcatch.owner.service.*;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class OwnerTokenRefreshConcurrencyIntegrationTest extends AbstractOwnerIntegrationTest {
    @Autowired OwnerLoginService login;
    @Autowired OwnerTokenRefreshService refresh;
    @Autowired OwnerSessionService sessionService;
    @Autowired OwnerAccessTokenPolicy policy;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtTokenProvider jwt;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private Long ownerId;
    private OwnerLoginRequest request;

    @BeforeEach
    void setUp() {
        reset(sessions, owners, clock, sessionTokens);
        request = new OwnerLoginRequest(UUID.randomUUID() + "@example.com", "password12");
        ownerId = owners.saveAndFlush(Owner.create(request.email(), encoder.encode(request.password()))).getId();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        jdbc.update("delete from owner_session where owner_id = ?", ownerId);
        owners.deleteById(ownerId);
    }

    @Test
    void 세션_외래_키는_없는_점주와_세션이_남은_점주의_삭제를_거부한다() {
        var issued = login.login(request);
        assertThatThrownBy(() -> jdbc.update("delete from owner where owner_id = ?", ownerId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update owner_session set owner_id = ? where session_key = ?", -1L, key(issued.accessToken())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(valid(issued.accessToken())).isTrue();
    }

    @Test
    void 두_기기의_동시_로그인과_갱신이_서로의_세션을_유지한다() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> login.login(request));
            var second = executor.submit(() -> login.login(request));
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.refreshToken()).isNotEqualTo(b.refreshToken());
            assertThat(valid(a.accessToken())).isTrue();
            assertThat(valid(b.accessToken())).isTrue();
            var renewed = refresh.refresh(a.refreshToken());
            assertThat(valid(renewed.accessToken())).isTrue();
            assertThat(valid(b.accessToken())).isTrue();
            assertThat(refresh.refresh(b.refreshToken()).refreshToken()).isNotBlank();
        }
    }

    @Test
    void 두_탭의_동시_재발급은_모두_성공하고_RT_쿠키는_한번만_교체한다() throws Exception {
        var issued = login.login(request);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<org.springframework.mock.web.MockHttpServletResponse> task = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(post("/v1/owner/auth/tokens:refresh")
                                .cookie(new Cookie("refreshToken", issued.refreshToken())))
                        .andExpect(status().isOk()).andReturn().getResponse();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(responses).filteredOn(response -> response.getCookie("refreshToken") != null).hasSize(1);
            var winner = responses.stream().filter(response -> response.getCookie("refreshToken") != null).findFirst().orElseThrow();
            assertThat(valid(winner.getCookie("ownerAccessToken").getValue())).isTrue();
            assertThat(refresh.refresh(winner.getCookie("refreshToken").getValue()).accessToken()).isNotBlank();
        }
    }

    @Test
    void 한_기기의_로그아웃은_다른_기기의_AT와_RT를_유지한다() throws Exception {
        var a = login.login(request);
        var b = login.login(request);
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("ownerAccessToken", a.accessToken())))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("ownerAccessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));
        assertThat(valid(a.accessToken())).isFalse();
        assertThatThrownBy(() -> refresh.refresh(a.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(valid(b.accessToken())).isTrue();
        assertThat(refresh.refresh(b.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void 유예_후_재사용은_해당_세션만_차단한다() {
        var instant = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        doReturn(instant).when(clock).instant();
        var a = login.login(request);
        var b = login.login(request);
        var renewed = refresh.refresh(a.refreshToken());
        doReturn(instant.plusSeconds(10)).when(clock).instant();
        assertThatThrownBy(() -> refresh.refresh(a.refreshToken())).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-004"));
        assertThat(valid(renewed.accessToken())).isFalse();
        assertThat(valid(b.accessToken())).isTrue();
        assertThat(refresh.refresh(b.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void Redis_장애는_세션별_로그인과_갱신을_막지_않는다() {
        doThrow(new DataAccessResourceFailureException("Redis")).when(sessionTokens)
                .publish(anyString(), anyLong(), anyString(), anyLong(), any());
        doThrow(new DataAccessResourceFailureException("Redis")).when(sessionTokens).consume(anyString());
        var a = login.login(request);
        var b = login.login(request);
        var renewed = refresh.refresh(a.refreshToken());
        assertThat(valid(renewed.accessToken())).isTrue();
        assertThat(valid(b.accessToken())).isTrue();
        verify(sessionTokens, times(3)).publish(anyString(), anyLong(), anyString(), anyLong(), any());
        verify(sessionTokens).consume(a.refreshToken());
    }

    @Test
    void 갱신_DB_롤백은_기존_RT를_유지한다() {
        var issued = login.login(request);
        doThrow(new DataAccessResourceFailureException("DB")).when(sessions).saveAndFlush(any(OwnerSession.class));
        assertThatThrownBy(() -> refresh.refresh(issued.refreshToken())).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
        reset(sessions);
        assertThat(valid(issued.accessToken())).isTrue();
        assertThat(refresh.refresh(issued.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test
    void 로그아웃_DB_롤백은_현재_AT와_RT를_유지한다() {
        var issued = login.login(request);
        doThrow(new DataAccessResourceFailureException("DB")).when(sessions).saveAndFlush(any(OwnerSession.class));
        assertThatThrownBy(() -> sessionService.logout(ownerId, key(issued.accessToken()))).isInstanceOf(AuthException.class);
        reset(sessions);
        assertThat(valid(issued.accessToken())).isTrue();
        assertThat(refresh.refresh(issued.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void 계정_전체_폐기는_모든_기기의_토큰을_차단한다() {
        var a = login.login(request);
        var b = login.login(request);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var owner = owners.findByIdForLogin(ownerId).orElseThrow();
            owner.clearRefreshToken();
            owners.saveAndFlush(owner);
        });
        assertThat(valid(a.accessToken())).isFalse();
        assertThat(valid(b.accessToken())).isFalse();
        assertThatThrownBy(() -> refresh.refresh(a.refreshToken())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> refresh.refresh(b.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(valid(login.login(request).accessToken())).isTrue();
    }

    @Test
    void 정지와_탈퇴는_갱신을_막고_기존_내정보_조회와_로그아웃은_유지한다() throws Exception {
        var a = login.login(request);
        jdbc.update("update owner set status='SUSPENDED', suspended_at=? where owner_id=?",
                java.time.LocalDateTime.now(), ownerId);
        assertThatThrownBy(() -> refresh.refresh(a.refreshToken())).isInstanceOf(AuthException.class);
        mvc.perform(get("/v1/owner/me").cookie(new Cookie("ownerAccessToken", a.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        jdbc.update("update owner set status='WITHDRAWN', withdrawn_at=? where owner_id=?",
                java.time.LocalDateTime.now(), ownerId);
        assertThatThrownBy(() -> refresh.refresh(a.refreshToken())).isInstanceOf(AuthException.class);
        mvc.perform(get("/v1/owner/me").cookie(new Cookie("ownerAccessToken", a.accessToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        mvc.perform(delete("/v1/owner/auth/tokens").cookie(new Cookie("ownerAccessToken", a.accessToken())))
                .andExpect(status().isNoContent());
        assertThat(valid(a.accessToken())).isFalse();
    }

    @Test
    void 이전_단일_토큰의_로그아웃은_새_기기별_세션을_유지한다() {
        var now = java.time.LocalDateTime.ofInstant(clock.instant(), com.lunchcatch.global.config.ClockConfig.ZONE);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            var owner = owners.findByIdForLogin(ownerId).orElseThrow();
            owner.recordLogin(com.lunchcatch.auth.opaque.TokenHasher.sha256("legacy-" + ownerId), now.plusDays(1), now);
            owners.saveAndFlush(owner);
        });
        var a = login.login(request);
        tokens.saveIfNewer("legacy-" + ownerId, ownerId, Role.OWNER, true, Duration.ofDays(1), 1L);
        var legacy = refresh.refresh("legacy-" + ownerId);
        assertThat(valid(legacy.accessToken())).isTrue();
        mvcLegacyLogout();
        assertThat(valid(legacy.accessToken())).isFalse();
        assertThat(valid(a.accessToken())).isTrue();
    }

    @Autowired OwnerLogoutService legacyLogout;

    private void mvcLegacyLogout() {
        legacyLogout.logout(ownerId);
    }

    private String key(String token) {
        return (String) jwt.getAdditionalClaims(token).get(OwnerSessionService.SESSION_CLAIM);
    }

    private boolean valid(String token) {
        return policy.isValid(Role.OWNER, ownerId, jwt.getIssuedAt(token), jwt.getAdditionalClaims(token),
                com.lunchcatch.auth.jwt.CutoffPolicy.REQUIRED);
    }

    @Test
    void Redis_장애중에도_DB_백업으로_회전하고_복구후_새_RT를_사용한다() {
        var issued = login.login(request);
        doThrow(new DataAccessResourceFailureException("Redis")).when(sessionTokens).consume(anyString());
        doThrow(new DataAccessResourceFailureException("Redis")).when(sessionTokens).publish(anyString(), anyLong(), anyString(), anyLong(), any());
        var renewed = refresh.refresh(issued.refreshToken());
        assertThat(valid(renewed.accessToken())).isTrue();
        reset(sessionTokens);
        assertThat(refresh.refresh(renewed.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void 캐시_전체_유실도_DB의_현재_세션을_검증한_뒤_복구한다() {
        var issued = login.login(request);
        var sessionKey = key(issued.accessToken());
        redis.delete(List.of(
                "ownerSessionToken:" + com.lunchcatch.auth.opaque.TokenHasher.sha256(issued.refreshToken()),
                "ownerSessionActive:" + sessionKey,
                "ownerSessionVersion:" + sessionKey));
        var renewed = refresh.refresh(issued.refreshToken());
        assertThat(sessionTokens.consume(renewed.refreshToken())).isPresent();
        assertThat(valid(renewed.accessToken())).isTrue();
    }

    @Test
    void 세션_DB_롤백후_Redis_소비표시를_복구한다() {
        var issued = login.login(request);
        doThrow(new DataAccessResourceFailureException("DB")).when(sessions).saveAndFlush(any(OwnerSession.class));
        assertThatThrownBy(() -> refresh.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
        reset(sessions);
        assertThat(redis.opsForValue().get("ownerSessionToken:" + com.lunchcatch.auth.opaque.TokenHasher.sha256(issued.refreshToken())))
                .doesNotContain("PENDING");
        assertThat(refresh.refresh(issued.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test
    void Redis_폐기_실패여도_DB가_로그아웃한_세션을_다시_허용하지_않는다() {
        var issued = login.login(request);
        var other = login.login(request);
        doThrow(new DataAccessResourceFailureException("Redis")).when(sessionTokens).revoke(anyString(), anyString(), anyLong(), any());
        assertThatThrownBy(() -> sessionService.logout(ownerId, key(issued.accessToken())))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
        reset(sessionTokens);
        assertThat(valid(other.accessToken())).isTrue();
        assertThat(refresh.refresh(other.refreshToken()).accessToken()).isNotBlank();
        assertThat(valid(issued.accessToken())).isFalse();
        assertThatThrownBy(() -> refresh.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
    }

}
