package com.launchcatch.owner;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.owner.repository.OwnerLogoutTokenRepository;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = OwnerLogoutTokenRepositoryIntegrationTest.RedisConfig.class)
class OwnerLogoutTokenRepositoryIntegrationTest {
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:9")
            .withExposedPorts(6379);

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired private OwnerLogoutTokenRepository logout;
    @Autowired private AccessTokenValidAfterRepository cutoff;
    @Autowired private com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository versions;

    @Test
    void 폐기한_순번의_지연_게시를_막고_DB_해시도_삭제한다() {
        repository.saveIfNewer("old", 801L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        logout.revokeThroughVersion(801L, TokenHasher.sha256("old"), 2L);
        assertThat(repository.find("old")).isEmpty();
        assertThat(repository.findActiveHash(Role.OWNER, 801L)).isEmpty();
        assertThat(repository.saveIfNewer("delayed", 801L, Role.OWNER, true, Duration.ofMinutes(1), 1L)).isFalse();
    }

    @Test
    void 늦게_도착한_로그아웃은_더_최신_로그인을_삭제하지_않는다() {
        repository.saveIfNewer("new", 802L, Role.OWNER, true, Duration.ofMinutes(1), 3L);
        logout.revokeThroughVersion(802L, TokenHasher.sha256("old"), 2L);
        assertThat(repository.find("new")).isPresent();
        assertThat(repository.findActiveHash(Role.OWNER, 802L)).contains(TokenHasher.sha256("new"));
    }

    @Test
    void DB에_RT가_없어도_남은_활성_토큰을_폐기하고_다시_시도할_수_있다() {
        repository.saveIfNewer("orphan", 803L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        logout.revokeThroughVersion(803L, null, 2L);
        logout.revokeThroughVersion(803L, null, 3L);
        assertThat(repository.find("orphan")).isEmpty();
        assertThat(repository.findActiveHash(Role.OWNER, 803L)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Role.class)
    void AT_차단_시각과_보관_시간이_뒤로_줄지_않는다(Role role) {
        LocalDateTime latest = LocalDateTime.of(2026, 10, 10, 12, 0, 0, 123456789);
        cutoff.invalidateBefore(role, 804L, latest, Duration.ofMinutes(30));
        cutoff.invalidateBefore(role, 804L, latest.minusSeconds(1), Duration.ofSeconds(1));
        cutoff.invalidateBefore(role, 804L, latest, Duration.ofSeconds(1));
        assertThat(cutoff.isValidAfter(role, 804L, latest.minusNanos(1))).isFalse();
        assertThat(cutoff.isValidAfter(role, 804L, latest)).isTrue();
        assertThat(redis.getExpire("accessTokenValidAfter:" + role.name() + ":804")).isGreaterThan(1700L);
    }

    @Test
    void 시계가_역행해도_로그아웃_전_AT는_차단하고_새_로그인은_허용한다() throws Exception {
        var owners = org.mockito.Mockito.mock(com.launchcatch.owner.repository.OwnerRepository.class);
        var manager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var owner = com.launchcatch.owner.entity.Owner.create("owner@example.com", "hash");
        var instant = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.now());
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneId.of("Asia/Seoul"); }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return instant.get(); }
        };
        var now = LocalDateTime.now(clock);
        owner.recordLogin("a".repeat(64), now.plusDays(14), now);
        org.mockito.Mockito.when(owners.findByIdForLogin(805L)).thenReturn(java.util.Optional.of(owner));
        org.mockito.Mockito.when(owners.findIssuanceVersionById(805L))
                .thenAnswer(invocation -> java.util.Optional.of(owner.getRefreshTokenIssuanceVersion()));
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var source = new com.launchcatch.owner.service.OwnerAccessTokenPolicy(owners, versions);
        var jwt = new com.launchcatch.auth.jwt.JwtTokenProvider(
                "test-secret-key-that-is-at-least-32-bytes-long-for-hmac", 1800000, 86400000,
                1209600000, 1209600000, clock, java.util.List.of(source));
        String oldToken;
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { oldToken = jwt.createAccessToken(805L, Role.OWNER); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
        // AT 발급 후 시계를 5분 뒤로 돌려도 폐기 순번은 그대로 증가한다.
        instant.set(instant.get().minusSeconds(300));
        var service = new com.launchcatch.owner.service.OwnerLogoutService(owners, logout, versions, manager);
        service.logout(805L);
        assertThat(jwt.validateToken(oldToken)).isTrue();
        assertThat(jwt.getIssuedAt(oldToken)).isAfter(LocalDateTime.now(clock));
        assertThat(authenticated(jwt, oldToken)).isFalse();
        owner.recordLogin("b".repeat(64), LocalDateTime.now(clock).plusDays(14), LocalDateTime.now(clock));
        String newToken;
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { newToken = jwt.createAccessToken(805L, Role.OWNER); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
        assertThat(authenticated(jwt, newToken)).isTrue();
        versions.invalidateThroughVersion(805L, 1L);
        assertThat(authenticated(jwt, oldToken)).isFalse();
        assertThat(authenticated(jwt, newToken)).isTrue();
    }

    @Test
    void 같은_초의_재로그인과_기존_JWT를_순번으로_구분한다() throws Exception {
        var version = new java.util.concurrent.atomic.AtomicLong(1);
        var owners = org.mockito.Mockito.mock(com.launchcatch.owner.repository.OwnerRepository.class);
        org.mockito.Mockito.when(owners.findIssuanceVersionById(806L)).thenAnswer(i -> java.util.Optional.of(version.get()));
        var policy = new com.launchcatch.owner.service.OwnerAccessTokenPolicy(owners, versions);
        com.launchcatch.auth.jwt.AccessTokenPolicy source = new com.launchcatch.auth.jwt.AccessTokenPolicy() {
            public Role role() { return Role.OWNER; }
            public java.util.Map<String, Object> additionalClaims(Long id) { return java.util.Map.of("issuanceVersion", Long.toString(version.get())); }
            public boolean isValid(Long id, java.util.Map<String, Object> claims) { return policy.isValid(id, claims); }
        };
        var clock = java.time.Clock.fixed(java.time.Instant.now(), java.time.ZoneId.of("Asia/Seoul"));
        var jwt = new com.launchcatch.auth.jwt.JwtTokenProvider("test-secret-key-that-is-at-least-32-bytes-long-for-hmac",
                1800000, 86400000, 1209600000, 1209600000, clock, java.util.List.of(source));
        var legacy = new com.launchcatch.auth.jwt.JwtTokenProvider("test-secret-key-that-is-at-least-32-bytes-long-for-hmac",
                1800000, 86400000, 1209600000, 1209600000, clock);
        String oldToken = jwt.createAccessToken(806L, Role.OWNER);
        String legacyToken = legacy.createAccessToken(806L, Role.OWNER);
        assertThat(authenticated(jwt, legacyToken)).isTrue();
        versions.invalidateThroughVersion(806L, 2);
        version.set(3);
        String newToken = jwt.createAccessToken(806L, Role.OWNER);
        assertThat(jwt.getIssuedAt(oldToken)).isEqualTo(jwt.getIssuedAt(newToken));
        assertThat(authenticated(jwt, oldToken)).isFalse();
        assertThat(authenticated(jwt, legacyToken)).isFalse();
        assertThat(authenticated(jwt, newToken)).isTrue();
        assertThat(redis.getExpire("accessTokenRevokedThroughVersion:OWNER:806")).isEqualTo(-1L);
        assertThat(authenticated(jwt, jwt.createAccessToken(806L, Role.ADMIN))).isTrue();
    }

    private boolean authenticated(com.launchcatch.auth.jwt.JwtTokenProvider jwt, String token) throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("accessToken", token));
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            new com.launchcatch.auth.jwt.JwtAuthenticationFilter(jwt, cutoff)
                    .doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
            return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null;
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisConfig {
        @Bean com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository versions(StringRedisTemplate redis) {
            return new com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository(redis);
        }

        @Bean OwnerLogoutTokenRepository logout(StringRedisTemplate redis) { return new OwnerLogoutTokenRepository(redis); }
        @Bean AccessTokenValidAfterRepository cutoff(StringRedisTemplate redis) { return new AccessTokenValidAfterRepository(redis); }

        @Bean
        LettuceConnectionFactory connectionFactory() {
            return new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        }

        @Bean
        StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }

        @Bean
        RefreshTokenRepository refreshTokenRepository(StringRedisTemplate redisTemplate) {
            return new RefreshTokenRepository(redisTemplate);
        }
    }
}
