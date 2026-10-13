package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.repository.OwnerRefreshTokenRepository;
import com.launchcatch.owner.service.OwnerLoginService;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// DB 행 잠금, 롤백 복구 및 로그인과 재발급의 게시 순서를 실제 저장소로 확인한다.
@Testcontainers
@SpringBootTest(properties = "jwt.secret=test-only-owner-concurrency-secret-at-least-32-bytes")
class OwnerTokenRefreshConcurrencyIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @MockitoSpyBean private OwnerRepository owners;
    @MockitoSpyBean private RefreshTokenRepository tokens;
    @MockitoSpyBean private OwnerRefreshTokenRepository ownerTokens;
    @Autowired private OwnerLoginService login;
    @Autowired private OwnerTokenRefreshService refresh;
    @Autowired private PasswordEncoder encoder;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private EntityManager entityManager;
    @MockitoSpyBean private Clock clock;
    private Long ownerId;
    private String oldToken;
    private final OwnerLoginRequest request = new OwnerLoginRequest("refresh@example.com", "password12");

    @BeforeEach
    void setUp() {
        ownerId = owners.saveAndFlush(Owner.create(request.email(), encoder.encode(request.password()))).getId();
        oldToken = login.login(request).refreshToken();
    }

    @AfterEach
    void tearDown() {
        owners.deleteById(ownerId);
    }

    @Test
    void DB와_Redis의_RT를_함께_회전하고_재사용은_현재_RT를_폐기한다() {
        var result = refresh.refresh(oldToken);
        assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(result.refreshToken()));
        assertThat(tokens.findActiveHash(Role.OWNER, ownerId)).contains(TokenHasher.sha256(result.refreshToken()));

        assertThatThrownBy(() -> refresh.refresh(oldToken)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-004"));
        assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isNull();
        assertThat(tokens.findActiveHash(Role.OWNER, ownerId)).isEmpty();
        assertThat(tokens.find(result.refreshToken())).isEmpty();
        assertThat(cutoff.isValidAfter(Role.OWNER, ownerId,
                owners.findById(ownerId).orElseThrow().getLastLoginAt().minusMinutes(1))).isFalse();
    }

    @Test
    void Redis_소비후_DB잠금전_중복요청은_첫_재발급을_폐기하지_않는다() throws Exception {
        CountDownLatch consumed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            var result = (OwnerRefreshTokenRepository.ConsumeOutcome) call.callRealMethod();
            if (result.isSuccess()) {
                consumed.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(ownerTokens).consumeForRotation(oldToken);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> refresh.refresh(oldToken));
            try {
                assertThat(consumed.await(15, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> refresh.refresh(oldToken)).isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-003"));
                assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(oldToken));
            } finally {
                release.countDown();
            }
            var winner = first.get(15, TimeUnit.SECONDS);
            assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(winner.refreshToken()));
            assertThat(tokens.findActiveHash(Role.OWNER, ownerId)).contains(TokenHasher.sha256(winner.refreshToken()));
        }
    }

    @Test
    void Redis_장애중_동일_RT의_DB_회전은_하나만_성공한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(ownerTokens).consumeForRotation(anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<String> task = () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return refresh.refresh(oldToken).refreshToken();
                } catch (AuthException e) {
                    assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-003");
                    return "rejected";
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var results = java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn("rejected"::equals).hasSize(1);
            String winner = results.stream().filter(value -> !value.equals("rejected")).findFirst().orElseThrow();
            assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(winner));
        }
    }

    @Test
    void 늦어진_재발급_게시는_더_최신_로그인을_덮어쓰지_않는다() throws Exception {
        CountDownLatch publicationReached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            publicationReached.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(tokens).saveIfNewer(anyString(), eq(ownerId), eq(Role.OWNER), eq(true), any(), eq(2L));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> refresh.refresh(oldToken));
            try {
                assertThat(publicationReached.await(15, TimeUnit.SECONDS)).isTrue();
                var latest = login.login(request);
                release.countDown();
                var earlier = pending.get(15, TimeUnit.SECONDS);
                assertThat(earlier.refreshToken()).isNotEqualTo(latest.refreshToken());
                assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(latest.refreshToken()));
                assertThat(tokens.findActiveHash(Role.OWNER, ownerId)).contains(TokenHasher.sha256(latest.refreshToken()));
                assertThat(tokens.find(earlier.refreshToken())).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void 이전_RT_재사용의_잠금_대기중_발급된_AT도_폐기한다() throws Exception {
        Instant startedAt = Instant.parse("2026-10-13T01:00:00Z");
        doReturn(startedAt).when(clock).instant();
        // 설정에서 발급한 RT와 같은 시계를 사용하도록 다시 로그인한다.
        String reusedToken = login.login(request).refreshToken();
        String currentToken = refresh.refresh(reusedToken).refreshToken();
        CountDownLatch revocationWaiting = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        AtomicReference<Thread> revokingThread = new AtomicReference<>();
        doAnswer(call -> {
            if (Thread.currentThread() == revokingThread.get()) {
                revocationWaiting.countDown();
                assertThat(releaseRevocation.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return Optional.ofNullable(entityManager.find(Owner.class, ownerId, LockModeType.PESSIMISTIC_WRITE));
        }).when(owners).findByIdForLogin(ownerId);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var revocation = executor.submit(() -> {
                revokingThread.set(Thread.currentThread());
                assertThatThrownBy(() -> refresh.refresh(reusedToken)).isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-004"));
            });
            try {
                assertThat(revocationWaiting.await(15, TimeUnit.SECONDS)).isTrue();
                // 초 경계에 고정해 AT 발급과 폐기 시각이 같아도 차단되는지 확인한다.
                doReturn(startedAt.plusSeconds(2)).when(clock).instant();
                var issuedWhileWaiting = refresh.refresh(currentToken);
                releaseRevocation.countDown();
                revocation.get(15, TimeUnit.SECONDS);
                assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isNull();
                assertThat(tokens.findActiveHash(Role.OWNER, ownerId)).isEmpty();
                assertThat(cutoff.isValidAfter(Role.OWNER, ownerId,
                        jwt.getIssuedAt(issuedWhileWaiting.accessToken()))).isFalse();
            } finally {
                releaseRevocation.countDown();
            }
        }
    }

    @Test
    void 실제_DB_롤백시_기존_토큰의_소비표시를_복구한다() {
        doThrow(new DataAccessResourceFailureException("DB save unavailable")).when(owners).saveAndFlush(any(Owner.class));
        assertThatThrownBy(() -> refresh.refresh(oldToken)).isInstanceOfSatisfying(AuthException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("AUTH-002"));
        assertThat(owners.findById(ownerId).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(oldToken));
        assertThat(ownerTokens.consumeForRotation(oldToken).isSuccess()).isTrue();
    }
}
