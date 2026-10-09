package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.admin.service.AdminLoginService;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 실제 MySQL과 Valkey로 게시 순서와 Redis 호출 중 DB 행 잠금 해제를 검증한다.
@Testcontainers
@SpringBootTest(properties = "jwt.secret=test-only-secret-not-used-anywhere-else-0123456789abcdef")
class AdminLogoutConcurrencyIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Container
    static final org.testcontainers.containers.GenericContainer<?> VALKEY =
            new org.testcontainers.containers.GenericContainer<>(DockerImageName.parse("valkey/valkey:9"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired private AdminRepository admins;
    @Autowired private AdminLoginService login;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private JwtTokenProvider jwt;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private RefreshTokenRepository cache;
    @Autowired private com.launchcatch.admin.service.AdminLogoutService logout;
    @Autowired private com.launchcatch.auth.jwt.AccessTokenValidAfterRepository cutoff;
    private Long adminId;

    @BeforeEach
    void setUp() {
        Admin admin = admins.saveAndFlush(Admin.register("concurrent.admin", encoder.encode("Freshman!2026"),
                "관리자", Role.ADMIN));
        adminId = admin.getId();
        when(jwt.getAccessTokenValidityMs()).thenReturn(1800000L);
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(Duration.ofDays(1).toMillis());
    }

    @AfterEach
    void tearDown() {
        admins.deleteById(adminId);
    }


    @Test
    void 로그아웃은_DB와_캐시를_폐기하고_이전_Access_Token을_차단한다() {
        var result = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
        var issued = java.time.LocalDateTime.now().minusSeconds(1);
        assertThat(cache.find(result.refreshToken())).isPresent();
        logout.logout(adminId, Role.ADMIN);
        Admin admin = admins.findById(adminId).orElseThrow();
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(cache.find(result.refreshToken())).isEmpty();
        assertThat(cache.findActiveHash(Role.ADMIN, adminId)).isEmpty();
        assertThat(cutoff.isValidAfter(Role.ADMIN, adminId, issued)).isFalse();
        assertThat(cutoff.isValidAfter(Role.ADMIN, adminId, java.time.LocalDateTime.now().plusSeconds(1))).isTrue();
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
        }).when(cache).saveIfNewer(any(), eq(adminId), eq(Role.ADMIN), eq(true), any(), org.mockito.ArgumentMatchers.anyLong());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026")));
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                logout.logout(adminId, Role.ADMIN);
            } finally { release.countDown(); }
            var earlier = delayed.get(30, TimeUnit.SECONDS);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isNull();
            assertThat(cache.find(earlier.refreshToken())).isEmpty();
            assertThat(cache.findActiveHash(Role.ADMIN, adminId)).isEmpty();
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
        }).when(cache).revokeBeforeVersion(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyLong());
        try (var workers = Executors.newSingleThreadExecutor()) {
            var delayed = workers.submit(() -> logout.logout(adminId, Role.ADMIN));
            com.launchcatch.admin.dto.AdminLoginResult latest;
            try {
                assertThat(waiting.await(30, TimeUnit.SECONDS)).isTrue();
                latest = login.login(new AdminLoginRequest("concurrent.admin", "Freshman!2026"));
            } finally { release.countDown(); }
            delayed.get(30, TimeUnit.SECONDS);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(latest.refreshToken()));
            assertThat(cache.findActiveHash(Role.ADMIN, adminId)).contains(TokenHasher.sha256(latest.refreshToken()));
            assertThat(cache.find(latest.refreshToken())).isPresent();
            assertThat(cache.find(first.refreshToken())).isEmpty();
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
        }).when(cache).revokeBeforeVersion(eq(adminId), eq(Role.ADMIN), org.mockito.ArgumentMatchers.anyLong());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> logout.logout(adminId, Role.ADMIN))
                .isInstanceOfSatisfying(com.launchcatch.auth.exception.AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(com.launchcatch.auth.exception.AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE));
        assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isNull();
        logout.logout(adminId, Role.ADMIN);
        assertThat(cache.findActiveHash(Role.ADMIN, adminId)).isEmpty();
        assertThat(cache.find(result.refreshToken())).get().extracting(RefreshTokenRepository.RefreshTokenData::id).isEqualTo(adminId);
        // 순번 폐기는 소유자를 보존한 tombstone을 남기므로 회전으로 유효화할 수 없다.
        assertThat(cache.compareAndRotate(result.refreshToken(), "new-token", Duration.ofDays(1)).status())
                .isEqualTo(RefreshTokenRepository.RotateOutcome.Status.REUSE_DETECTED);
    }

    @Test
    void 병렬_로그아웃의_늦은_폐기기준이_최신_차단을_되돌리지_않는다() {
        var early = java.time.LocalDateTime.of(2026, 10, 9, 12, 0, 0, 100000000);
        var latest = early.plusSeconds(2);
        cutoff.invalidateBefore(Role.ADMIN, adminId, latest, Duration.ofMinutes(30));
        cutoff.invalidateBefore(Role.ADMIN, adminId, early, Duration.ofMinutes(30));
        assertThat(cutoff.isValidAfter(Role.ADMIN, adminId, early.plusSeconds(1))).isFalse();
        assertThat(cutoff.isValidAfter(Role.ADMIN, adminId, latest)).isTrue();
    }
}
