package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.repository.OwnerRefreshTokenRepository;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// DB 행 잠금, 롤백 복구 및 로그인과 재발급의 게시 순서를 실제 저장소로 확인한다.
class OwnerLegacyTokenRefreshConcurrencyIntegrationTest extends AbstractOwnerIntegrationTest {

    @Autowired private OwnerTokenRefreshService refresh;
    @Autowired private com.launchcatch.owner.service.OwnerLogoutService logout;
    @Autowired private com.launchcatch.owner.service.OwnerAccessTokenPolicy accessTokenPolicy;
    @Autowired private PasswordEncoder encoder;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private EntityManager entityManager;
    private Long ownerId;
    private String oldToken;
    private final OwnerLoginRequest request = new OwnerLoginRequest("refresh@example.com", "password12");

    @BeforeEach
    void setUp() {
        ownerId = owners.saveAndFlush(Owner.create(request.email(), encoder.encode(request.password()))).getId();
        oldToken = legacyLogin().refreshToken();
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
        assertThat(versions.isValid(ownerId, Long.parseLong((String) jwt.getAdditionalClaims(result.accessToken()).get("issuanceVersion")))).isFalse();
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
                var latest = legacyLogin();
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
        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        doReturn(startedAt).when(clock).instant();
        // 설정에서 발급한 RT와 같은 시계를 사용하도록 다시 로그인한다.
        String reusedToken = legacyLogin().refreshToken();
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
                assertThat(versions.isValid(ownerId, Long.parseLong((String) jwt.getAdditionalClaims(issuedWhileWaiting.accessToken()).get("issuanceVersion")))).isFalse();
            } finally {
                releaseRevocation.countDown();
            }
        }
    }

    @Test
    void 로그아웃은_재발급한_AT와_RT를_폐기하고_새_로그인의_재발급은_허용한다() {
        var issued = refresh.refresh(oldToken);
        assertThat(authenticated(issued.accessToken())).isTrue();
        logout.logout(ownerId);
        assertThat(authenticated(issued.accessToken())).isFalse();
        assertThat(tokens.find(issued.refreshToken())).isEmpty();
        assertThatThrownBy(() -> refresh.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
        var loggedInAgain = legacyLogin();
        assertThat(authenticated(loggedInAgain.accessToken())).isTrue();
        var refreshedAgain = refresh.refresh(loggedInAgain.refreshToken());
        assertThat(authenticated(refreshedAgain.accessToken())).isTrue();
        assertThat(authenticated(issued.accessToken())).isFalse();
    }

    @Test
    void Redis_폐기가_실패해도_DB에_확정된_로그아웃은_AT와_RT를_차단한다() {
        var issued = refresh.refresh(oldToken);
        doThrow(new DataAccessResourceFailureException("RT Redis")).when(logoutTokens)
                .revokeThroughVersion(anyLong(), anyString(), anyLong());
        doThrow(new DataAccessResourceFailureException("AT Redis")).when(versions)
                .invalidateThroughVersion(anyLong(), anyLong());
        assertThatThrownBy(() -> logout.logout(ownerId)).isInstanceOf(AuthException.class);
        var owner = owners.findById(ownerId).orElseThrow();
        assertThat(owner.getRefreshTokenHash()).isNull();
        assertThat(owner.getAccessTokenRevokedVersion()).isEqualTo(owner.getRefreshTokenIssuanceVersion());
        doThrow(new DataAccessResourceFailureException("Redis lookup")).when(versions).isValid(anyLong(), anyLong());
        assertThat(authenticated(issued.accessToken())).isFalse();
        doThrow(new DataAccessResourceFailureException("Redis consume")).when(ownerTokens).consumeForRotation(anyString());
        assertThatThrownBy(() -> refresh.refresh(issued.refreshToken())).isInstanceOf(AuthException.class);
    }

    @Test
    void Redis_차단_키가_유실되어도_DB가_재사용으로_폐기한_AT를_거부한다() {
        var issued = refresh.refresh(oldToken);
        assertThatThrownBy(() -> refresh.refresh(oldToken)).isInstanceOf(AuthException.class);
        redis.delete("accessTokenRevokedThroughVersion:OWNER:" + ownerId);
        assertThat(authenticated(issued.accessToken())).isFalse();
        var loggedInAgain = legacyLogin();
        assertThat(authenticated(loggedInAgain.accessToken())).isTrue();
    }

    @Test
    void 로그아웃_DB_롤백시_현재_AT와_RT는_유효하다() {
        var issued = refresh.refresh(oldToken);
        doThrow(new DataAccessResourceFailureException("DB save")).when(owners).saveAndFlush(any(Owner.class));
        assertThatThrownBy(() -> logout.logout(ownerId)).isInstanceOf(AuthException.class);
        var owner = owners.findById(ownerId).orElseThrow();
        assertThat(owner.getAccessTokenRevokedVersion()).isEqualTo(-1L);
        assertThat(owner.getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(issued.refreshToken()));
        assertThat(authenticated(issued.accessToken())).isTrue();
        assertThat(tokens.find(issued.refreshToken())).isPresent();
    }

    @Test
    void 재발급_RT는_재발급_시점부터_1일_유효하다() {
        assertThat(jwt.refreshTokenValidityMs(Role.OWNER)).isEqualTo(java.time.Duration.ofDays(1).toMillis());
        var before = java.time.LocalDateTime.ofInstant(clock.instant(), com.launchcatch.global.config.ClockConfig.ZONE);
        var issued = refresh.refresh(oldToken);
        var after = java.time.LocalDateTime.ofInstant(clock.instant(), com.launchcatch.global.config.ClockConfig.ZONE);
        var owner = owners.findById(ownerId).orElseThrow();
        assertThat(owner.getRefreshTokenExpiresAt()).isBetween(before.plusDays(1), after.plusDays(1));
        assertThat(tokens.find(issued.refreshToken())).isPresent();
    }

    @Test
    void 기존_계정_마이그레이션은_현재_RT를_보존하고_이전_AT를_차단한다() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE owner (owner_id BIGINT PRIMARY KEY, refresh_token_hash VARCHAR(64), refresh_token_issuance_version BIGINT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO owner VALUES (1, NULL, 0), (2, 'active', 5), (3, NULL, 6)");
            var resource = new org.springframework.core.io.ClassPathResource("db/migration/owner/V4__owner_access_token_revoked_version.sql");
            String migration;
            try (var input = resource.getInputStream()) {
                // MySQL은 임시 테이블의 CHECK 이름도 스키마 안에서 고유해야 한다.
                migration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("chk_owner_access_token_revoked_version", "chk_owner_migration_test_revoked_version");
            }
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ByteArrayResource(migration.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try (var rows = statement.executeQuery("SELECT access_token_revoked_version FROM owner ORDER BY owner_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(-1L);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(4L);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(6L);
                assertThat(rows.next()).isFalse();
            }
        }
    }


    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // 배포 전 형식의 RT와 AT를 실제 DB·Redis에 준비한다.
    private com.launchcatch.owner.service.OwnerLoginResult legacyLogin() {
        String token = com.launchcatch.auth.opaque.OpaqueTokenGenerator.generate();
        var ttl = java.time.Duration.ofMillis(jwt.refreshTokenValidityMs(Role.OWNER));
        var result = new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx -> {
            var owner = owners.findByIdForLogin(ownerId).orElseThrow();
            var now = java.time.LocalDateTime.ofInstant(clock.instant(), com.launchcatch.global.config.ClockConfig.ZONE);
            owner.recordLogin(TokenHasher.sha256(token), now.plus(ttl), now);
            owners.saveAndFlush(owner);
            return new com.launchcatch.owner.service.OwnerLoginResult(
                    new com.launchcatch.owner.dto.OwnerLoginResponse(owner.getEmail(), Role.OWNER, owner.getStatus(), null),
                    jwt.createAccessToken(ownerId, Role.OWNER,
                            com.launchcatch.owner.service.OwnerAccessTokenPolicy.additionalClaims(owner.getRefreshTokenIssuanceVersion())), token);
        });
        long version = owners.findById(ownerId).orElseThrow().getRefreshTokenIssuanceVersion();
        tokens.saveIfNewer(token, ownerId, Role.OWNER, true, ttl, version);
        return result;
    }

    private boolean authenticated(String token) {
        return accessTokenPolicy.isValid(Role.OWNER, ownerId, jwt.getIssuedAt(token), jwt.getAdditionalClaims(token),
                com.launchcatch.auth.jwt.CutoffPolicy.REQUIRED);
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
