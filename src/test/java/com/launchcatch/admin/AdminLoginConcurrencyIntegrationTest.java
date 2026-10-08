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

// DB 커밋과 행 잠금은 실제 MySQL에서 검증하고 Redis 게시 결과만 테스트 더블로 관찰한다.
@Testcontainers
@SpringBootTest(properties = "jwt.secret=test-only-secret-not-used-anywhere-else-0123456789abcdef")
class AdminLoginConcurrencyIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private AdminRepository admins;
    @Autowired private AdminLoginService login;
    @Autowired private PasswordEncoder encoder;
    @MockitoBean private JwtTokenProvider jwt;
    @MockitoBean private RefreshTokenRepository cache;
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
            return null;
        }).when(cache).save(any(), eq(adminId), eq(Role.ADMIN), eq(true), any());

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
}
