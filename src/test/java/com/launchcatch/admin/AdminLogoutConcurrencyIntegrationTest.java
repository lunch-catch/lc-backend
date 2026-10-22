package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.service.AdminLoginService;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

// 실제 MySQL과 Valkey로 게시 순서와 Redis 호출 중 DB 행 잠금 해제를 검증한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminLogoutConcurrencyIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired private AdminLoginService login;
    @Autowired private com.launchcatch.admin.service.AdminLogoutService logout;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private com.launchcatch.admin.repository.AdminAccessTokenVersionRepository versions;
    @Autowired private com.launchcatch.admin.service.AdminTokenService reissue;
    @Autowired private com.launchcatch.admin.service.AdminAccessTokenVerifier verifier;
    private Long adminId;

    @BeforeEach
    void setUp() {
        Admin admin = admins.saveAndFlush(Admin.register("concurrent.admin", encoder.encode("Freshman!2026"),
                "관리자", Role.ADMIN));
        adminId = admin.getId();
        doReturn(1800000L).when(jwt).getAccessTokenValidityMs();
        doReturn(Duration.ofDays(1).toMillis()).when(jwt).refreshTokenValidityMs(Role.ADMIN);
    }

    @AfterEach
    void tearDown() {
        admins.deleteById(adminId);
    }

    @Test
    void DB승인_후_JWT생성이_지연되어도_로그아웃이_차단하고_같은초_재로그인은_허용한다() throws Exception {
        var now = java.time.Instant.now();
        var realJwt = new JwtTokenProvider("test-only-secret-for-delayed-jwt-0123456789abcdef",
                1800000L, 86400000L, 1209600000L, 1209600000L,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var signatures = new AtomicInteger();
        doAnswer(call -> {
            if (signatures.incrementAndGet() == 1) {
                waiting.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("JWT wait timed out"); }
            }
            return realJwt.createAccessToken(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(jwt).createAccessToken(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyMap());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026")));
            com.launchcatch.admin.dto.AdminLoginResult fresh;
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                logout.logout(adminId, Role.ADMIN);
                fresh = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
            } finally { release.countDown(); }
            var old = delayed.get(30, TimeUnit.SECONDS);
            assertThat(realJwt.getIssuedAt(old.accessToken())).isEqualTo(realJwt.getIssuedAt(fresh.accessToken()));
            assertThat(AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(old.accessToken()))).isEqualTo(1L);
            assertThat(AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(fresh.accessToken()))).isEqualTo(3L);
            assertThat(versions.isVersionValid(Role.ADMIN, adminId, AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(old.accessToken())))).isFalse();
            assertThat(versions.isVersionValid(Role.ADMIN, adminId, AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(fresh.accessToken())))).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 재발급_DB커밋_후_지연된_JWT는_로그아웃으로_차단한다(boolean cacheLost) throws Exception {
        var initial = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        if (cacheLost) {
            tokens.revokeIfActiveHashMatches(TokenHasher.sha256(initial.refreshToken()), Role.ADMIN, adminId);
        }
        var now = java.time.Instant.now();
        var realJwt = new JwtTokenProvider("test-only-secret-for-delayed-jwt-0123456789abcdef",
                1800000L, 86400000L, 1209600000L, 1209600000L,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var signatures = new AtomicInteger();
        doAnswer(call -> {
            if (signatures.incrementAndGet() == 1) {
                waiting.countDown();
                if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("JWT wait timed out"); }
            }
            return realJwt.createAccessToken(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(jwt).createAccessToken(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyMap());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> reissue.reissue(initial.refreshToken()));
            com.launchcatch.admin.dto.AdminLoginResult fresh;
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                logout.logout(adminId, Role.ADMIN);
                fresh = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
            } finally { release.countDown(); }
            var old = delayed.get(30, TimeUnit.SECONDS);
            assertThat(realJwt.getIssuedAt(old.accessToken())).isEqualTo(realJwt.getIssuedAt(fresh.accessToken()));
            assertThat(AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(old.accessToken()))).isEqualTo(2L);
            assertThat(AdminAccessTokenClaims.issuanceVersion(realJwt.getAdditionalClaims(fresh.accessToken()))).isEqualTo(4L);
            assertThat(verifier.isValid(Role.ADMIN, adminId, realJwt.getIssuedAt(old.accessToken()),
                    realJwt.getAdditionalClaims(old.accessToken()), com.launchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isFalse();
            assertThat(verifier.isValid(Role.ADMIN, adminId, realJwt.getIssuedAt(fresh.accessToken()),
                    realJwt.getAdditionalClaims(fresh.accessToken()), com.launchcatch.auth.jwt.CutoffPolicy.REQUIRED)).isTrue();
            assertThat(tokens.find(old.refreshToken())).isEmpty();
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash())
                    .isEqualTo(TokenHasher.sha256(fresh.refreshToken()));
        }
    }

    @Test
    void 로그아웃은_DB와_캐시를_폐기하고_이전_Access_Token을_차단한다() {
        var result = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        var issued = java.time.LocalDateTime.now().minusSeconds(1);
        assertThat(tokens.find(result.refreshToken())).isPresent();
        logout.logout(adminId, Role.ADMIN);
        Admin admin = admins.findById(adminId).orElseThrow();
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(tokens.find(result.refreshToken())).isEmpty();
        assertThat(tokens.findActiveHash(Role.ADMIN, adminId)).isEmpty();
        assertThat(legacyCutoff.isValidAfter(Role.ADMIN, adminId, issued)).isFalse();
        assertThat(legacyCutoff.isValidAfter(Role.ADMIN, adminId, java.time.LocalDateTime.now().plusSeconds(1))).isTrue();
    }

    @Test
    void 로그아웃_후_도착한_이전_로그인의_캐시게시를_거부한다() throws Exception {
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            waiting.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("publication wait timed out"); }
            return call.callRealMethod();
        }).when(tokens).saveIfNewer(any(), eq(adminId), eq(Role.ADMIN), eq(true), any(), org.mockito.ArgumentMatchers.anyLong());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026")));
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                logout.logout(adminId, Role.ADMIN);
            } finally { release.countDown(); }
            var earlier = delayed.get(30, TimeUnit.SECONDS);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isNull();
            assertThat(tokens.find(earlier.refreshToken())).isEmpty();
            assertThat(tokens.findActiveHash(Role.ADMIN, adminId)).isEmpty();
        }
    }

    @Test
    void 로그아웃_DB확정_후_새_로그인이_게시되면_이전_정리는_새토큰을_보존한다() throws Exception {
        var first = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            waiting.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("revocation wait timed out"); }
            return call.callRealMethod();
        }).when(tokens).revokeBeforeVersion(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyLong());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> logout.logout(adminId, Role.ADMIN));
            com.launchcatch.admin.dto.AdminLoginResult latest;
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                latest = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
            } finally { release.countDown(); }
            delayed.get(30, TimeUnit.SECONDS);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(latest.refreshToken()));
            assertThat(tokens.findActiveHash(Role.ADMIN, adminId)).contains(TokenHasher.sha256(latest.refreshToken()));
            assertThat(tokens.find(latest.refreshToken())).isPresent();
            assertThat(tokens.find(first.refreshToken())).isEmpty();
        }
    }

    @Test
    void DB폐기_후_캐시실패의_재요청은_남은_토큰을_정리한다() {
        var result = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        var attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new org.springframework.dao.QueryTimeoutException("redis temporary outage");
            }
            return call.callRealMethod();
        }).when(tokens).revokeBeforeVersion(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyLong());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> logout.logout(adminId, Role.ADMIN))
                .isInstanceOfSatisfying(com.launchcatch.auth.exception.AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(com.launchcatch.auth.exception.AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE));
        assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isNull();
        logout.logout(adminId, Role.ADMIN);
        assertThat(tokens.findActiveHash(Role.ADMIN, adminId)).isEmpty();
        assertThat(tokens.find(result.refreshToken())).get().extracting(RefreshTokenRepository.RefreshTokenData::id).isEqualTo(adminId);
        // 순번 폐기는 소유자를 보존한 tombstone을 남기므로 회전으로 유효화할 수 없다.
        assertThat(tokens.compareAndRotate(result.refreshToken(), "new-token", Duration.ofDays(1)).status())
                .isEqualTo(RefreshTokenRepository.RotateOutcome.Status.REUSE_DETECTED);
    }

    @Test
    void 병렬_로그아웃의_늦은_폐기기준이_최신_차단을_되돌리지_않는다() {
        var early = java.time.LocalDateTime.of(2026, 10, 9, 12, 0, 0, 100000000);
        var latest = early.plusSeconds(2);
        legacyCutoff.invalidateBefore(Role.ADMIN, adminId, latest, Duration.ofMinutes(30));
        legacyCutoff.invalidateBefore(Role.ADMIN, adminId, early, Duration.ofMinutes(30));
        assertThat(legacyCutoff.isValidAfter(Role.ADMIN, adminId, early.plusSeconds(1))).isFalse();
        assertThat(legacyCutoff.isValidAfter(Role.ADMIN, adminId, latest)).isTrue();
    }

    @Test
    void 마지막_Redis_저장실패_후_커밋된_DB가_두_JWT를_계속_차단한다() {
        login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        var before = admins.findById(adminId).orElseThrow();
        long version = before.getRefreshTokenIssuanceVersion();
        var issuedAt = java.time.LocalDateTime.now().minusSeconds(1);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
                .when(legacyCutoff).invalidateBefore(eq(Role.ADMIN), eq(adminId), any(), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> logout.logout(adminId, Role.ADMIN))
                .isInstanceOf(com.launchcatch.auth.exception.AuthException.class);
        var committed = admins.findById(adminId).orElseThrow();
        assertThat(committed.getRefreshTokenHash()).isNull();
        assertThat(committed.getAccessTokenRevokedVersion()).isGreaterThanOrEqualTo(version);
        assertThat(committed.getAccessTokenValidAfter()).isAfter(issuedAt);
        assertThat(versions.isVersionValid(Role.ADMIN, adminId, version)).isFalse();
        redis.delete(java.util.List.of("accessTokenRevokedVersion:ADMIN:" + adminId,
                "accessTokenValidAfter:ADMIN:" + adminId));
        assertThat(verifier.isValidAfter(Role.ADMIN, adminId, issuedAt, version,
                com.launchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
        assertThat(verifier.isValidAfter(Role.ADMIN, adminId, issuedAt, null,
                com.launchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
    }
}
