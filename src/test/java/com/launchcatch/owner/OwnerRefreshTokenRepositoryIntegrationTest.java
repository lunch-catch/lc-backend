package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.owner.repository.OwnerRefreshTokenRepository;
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
@SpringBootTest(classes = OwnerRefreshTokenRepositoryIntegrationTest.RedisConfig.class)
class OwnerRefreshTokenRepositoryIntegrationTest {
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:9").withExposedPorts(6379);
    @Autowired private RefreshTokenRepository repository;
    @Autowired private OwnerRefreshTokenRepository ownerTokens;
    @Autowired private StringRedisTemplate redis;

    @Test
    void 소비는_한번만_성공하고_게시전_활성포인터는_그대로다() {
        repository.saveIfNewer("consume-old", 710L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        assertThat(ownerTokens.consumeForRotation("consume-old").isSuccess()).isTrue();
        assertThat(ownerTokens.consumeForRotation("consume-old").status()).isEqualTo(OwnerRefreshTokenRepository.ConsumeOutcome.Status.IN_PROGRESS);
        assertThat(repository.findActiveHash(Role.OWNER, 710L)).contains(TokenHasher.sha256("consume-old"));
        assertThat(redis.getExpire("refreshToken:" + TokenHasher.sha256("consume-old"))).isBetween(1L, 60L);
    }

    @Test
    void 다른_역할의_토큰은_소비하지_않는다() {
        repository.saveIfNewer("admin-token", 711L, Role.ADMIN, true, Duration.ofMinutes(1), 1L);
        assertThat(ownerTokens.consumeForRotation("admin-token").data().role()).isEqualTo(Role.ADMIN);
        assertThat(repository.find("admin-token").orElseThrow().remember()).isTrue();
        assertThat(repository.compareAndRotate("admin-token", "admin-new", Duration.ofMinutes(1)).isSuccess()).isTrue();
    }

    @Test
    void DB_롤백후_소비표시를_복구하고_남은_TTL을_유지한다() {
        repository.saveIfNewer("rollback-old", 712L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        ownerTokens.consumeForRotation("rollback-old");
        ownerTokens.restoreConsumed(TokenHasher.sha256("rollback-old"));
        assertThat(ownerTokens.consumeForRotation("rollback-old").isSuccess()).isTrue();
        assertThat(redis.getExpire("refreshToken:" + TokenHasher.sha256("rollback-old"))).isBetween(1L, 60L);
    }

    @Test
    void 재발급_게시가_늦어도_나중_로그인을_덮어쓰지_않는다() {
        repository.saveIfNewer("refresh-base", 713L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        ownerTokens.consumeForRotation("refresh-base");
        repository.saveIfNewer("login-winner", 713L, Role.OWNER, true, Duration.ofMinutes(1), 3L);
        assertThat(repository.saveIfNewer("late-refresh", 713L, Role.OWNER, true, Duration.ofMinutes(1), 2L)).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 713L)).contains(TokenHasher.sha256("login-winner"));
    }

    @Test
    void 재사용_폐기_순번이_이전_게시를_차단한다() {
        repository.saveIfNewer("revoked-current", 714L, Role.OWNER, true, Duration.ofMinutes(1), 2L);
        ownerTokens.revokeThroughVersion(714L, TokenHasher.sha256("revoked-current"), 3L);
        assertThat(repository.find("revoked-current")).isEmpty();
        assertThat(repository.findActiveHash(Role.OWNER, 714L)).isEmpty();
        assertThat(repository.saveIfNewer("delayed-before-revoke", 714L, Role.OWNER, true, Duration.ofMinutes(1), 2L)).isFalse();
        assertThat(repository.saveIfNewer("login-after-revoke", 714L, Role.OWNER, true, Duration.ofMinutes(1), 4L)).isTrue();
    }

    @Test
    void 지연된_폐기는_더_최신_로그인을_삭제하지_않는다() {
        repository.saveIfNewer("latest-after-revoke", 715L, Role.OWNER, true, Duration.ofMinutes(1), 4L);
        ownerTokens.revokeThroughVersion(715L, null, 3L);
        assertThat(repository.find("latest-after-revoke")).isPresent();
        assertThat(repository.findActiveHash(Role.OWNER, 715L)).contains(TokenHasher.sha256("latest-after-revoke"));
    }

    @Test
    void 동시_소비는_하나만_성공한다() throws Exception {
        repository.saveIfNewer("concurrent-refresh", 716L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<OwnerRefreshTokenRepository.ConsumeOutcome> task = () -> {
                start.await();
                return ownerTokens.consumeForRotation("concurrent-refresh");
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertThat(java.util.List.of(first.get().status(), second.get().status())).containsExactlyInAnyOrder(
                    OwnerRefreshTokenRepository.ConsumeOutcome.Status.SUCCESS, OwnerRefreshTokenRepository.ConsumeOutcome.Status.IN_PROGRESS);
        }
    }

    @Test
    void 처리_완료후에만_재사용으로_판정하고_복구하지_않는다() {
        repository.saveIfNewer("confirmed", 717L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        ownerTokens.consumeForRotation("confirmed");
        ownerTokens.confirmConsumed(TokenHasher.sha256("confirmed"));
        ownerTokens.restoreConsumed(TokenHasher.sha256("confirmed"));
        assertThat(ownerTokens.consumeForRotation("confirmed").isReuseDetected()).isTrue();
        assertThat(redis.getExpire("refreshToken:" + TokenHasher.sha256("confirmed"))).isBetween(1L, 60L);
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisConfig {
        @Bean LettuceConnectionFactory connectionFactory() {
            return new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
        @Bean RefreshTokenRepository refreshTokenRepository(StringRedisTemplate redis) {
            return new RefreshTokenRepository(redis);
        }
        @Bean OwnerRefreshTokenRepository ownerRefreshTokenRepository(StringRedisTemplate redis) {
            return new OwnerRefreshTokenRepository(redis);
        }
    }
}
