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
class AdminLoginConcurrencyIntegrationTest {
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
    private Long adminId;

    @BeforeEach
    void setUp() {
        Admin admin = admins.saveAndFlush(Admin.register("concurrent.admin", encoder.encode("Freshman!2026"),
                "관리자", Role.ADMIN));
        adminId = admin.getId();
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(Duration.ofDays(1).toMillis());
    }

    @AfterEach
    void tearDown() {
        admins.deleteById(adminId);
    }

    @Test
    void 이전_로그인의_게시가_늦어져도_DB와_캐시는_최신_로그인_토큰을_유지한다() throws Exception {
        CountDownLatch firstBackupCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger signatures = new AtomicInteger();
        var publishedHashes = new CopyOnWriteArrayList<String>();
        when(jwt.createAccessToken(adminId, Role.ADMIN)).thenAnswer(call -> {
            int sequence = signatures.incrementAndGet();
            if (sequence == 1) {
                firstBackupCommitted.countDown();
                if (!releaseFirst.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First login was not released");
                }
            }
            return "test-access-token-" + sequence;
        });
        doAnswer(call -> {
            publishedHashes.add(TokenHasher.sha256(call.getArgument(0)));
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            return call.callRealMethod();
        }).when(cache).saveIfNewer(any(), eq(adminId), eq(Role.ADMIN), eq(true), any(), org.mockito.ArgumentMatchers.anyLong());

        var workers = Executors.newFixedThreadPool(2);
        try {
            var request = new AdminLoginRequest("concurrent.admin", "Freshman!2026");
            var first = workers.submit(() -> login.login(request));
            assertThat(firstBackupCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> login.login(request)).get(30, TimeUnit.SECONDS);
            String latestHash = TokenHasher.sha256(second.refreshToken());
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isEqualTo(latestHash);
            releaseFirst.countDown();
            var earlier = first.get(30, TimeUnit.SECONDS);
            assertThat(TokenHasher.sha256(earlier.refreshToken())).isNotEqualTo(latestHash);
            assertThat(publishedHashes).containsExactly(latestHash);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isEqualTo(latestHash);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test
    void Redis_게시가_지연되어도_다른_로그인은_DB_잠금없이_완료하고_이전_게시를_거부한다() throws Exception {
        when(jwt.createAccessToken(adminId, Role.ADMIN)).thenReturn("test-access-token");
        var publicationEntered = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            if (calls.incrementAndGet() == 1) {
                publicationEntered.countDown();
                if (!releasePublication.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Publication was not released");
                }
            }
            return call.callRealMethod();
        }).when(cache).saveIfNewer(any(), eq(adminId), eq(Role.ADMIN), eq(true), any(),
                org.mockito.ArgumentMatchers.anyLong());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var request = new AdminLoginRequest("concurrent.admin", "Freshman!2026");
            var first = workers.submit(() -> login.login(request));
            assertThat(publicationEntered.await(30, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> login.login(request)).get(10, TimeUnit.SECONDS);
            var latestHash = TokenHasher.sha256(second.refreshToken());
            assertThat(cache.findActiveHash(Role.ADMIN, adminId)).contains(latestHash);
            releasePublication.countDown();
            var earlier = first.get(30, TimeUnit.SECONDS);
            assertThat(cache.find(earlier.refreshToken())).isEmpty();
            assertThat(cache.findActiveHash(Role.ADMIN, adminId)).contains(latestHash);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenHash()).isEqualTo(latestHash);
            assertThat(admins.findById(adminId).orElseThrow().getRefreshTokenIssuanceVersion()).isEqualTo(2L);
        } finally {
            releasePublication.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void Lua는_큰_순번과_중복_게시도_정확히_비교한다() {
        assertThat(cache.saveIfNewer("latest", adminId, Role.ADMIN, true, Duration.ofDays(1), Long.MAX_VALUE)).isTrue();
        assertThat(cache.saveIfNewer("earlier", adminId, Role.ADMIN, true, Duration.ofDays(1), Long.MAX_VALUE - 1)).isFalse();
        assertThat(cache.saveIfNewer("duplicate", adminId, Role.ADMIN, true, Duration.ofDays(1), Long.MAX_VALUE)).isFalse();
        assertThat(cache.findActiveHash(Role.ADMIN, adminId)).contains(TokenHasher.sha256("latest"));
        assertThat(cache.find("earlier")).isEmpty();
        assertThat(cache.find("duplicate")).isEmpty();
    }

}
