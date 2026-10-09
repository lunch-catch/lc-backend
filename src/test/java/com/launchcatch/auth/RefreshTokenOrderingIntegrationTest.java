package com.launchcatch.auth;

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
@SpringBootTest(classes = RefreshTokenOrderingIntegrationTest.RedisConfig.class)
class RefreshTokenOrderingIntegrationTest {
    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:9")
            .withExposedPorts(6379);

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void 역순_게시에도_최신_RT와_활성_포인터를_유지한다() {
        repository.saveIfNewer("new-login", 701L, Role.OWNER, true, Duration.ofMinutes(1), 2L);
        boolean oldSaved = repository.saveIfNewer("old-login", 701L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        assertThat(oldSaved).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 701L)).contains(TokenHasher.sha256("new-login"));
        assertThat(repository.find("new-login")).isPresent();
        assertThat(repository.find("old-login")).isEmpty();
    }

    @Test
    void Lua_53비트_범위를_넘는_인접_순번도_정확히_비교한다() {
        repository.saveIfNewer("max-login", 702L, Role.OWNER, true, Duration.ofMinutes(1), Long.MAX_VALUE);
        assertThat(repository.saveIfNewer("previous-login", 702L, Role.OWNER, true,
                Duration.ofMinutes(1), Long.MAX_VALUE - 1)).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 702L)).contains(TokenHasher.sha256("max-login"));
    }

    @Test
    void 토큰_키가_사라져도_이전_게시를_되살리지_않는다() {
        repository.saveIfNewer("expired-login", 703L, Role.OWNER, true, Duration.ofMinutes(1), 2L);
        redis.delete("refreshToken:" + TokenHasher.sha256("expired-login"));
        repository.deleteActiveKey(Role.OWNER, 703L);
        assertThat(redis.getExpire("refreshTokenIssuanceVersion:OWNER:703")).isEqualTo(-1L);
        assertThat(repository.saveIfNewer("delayed-login", 703L, Role.OWNER, true, Duration.ofMinutes(1), 1L)).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 703L)).isEmpty();
        assertThat(repository.find("delayed-login")).isEmpty();
    }

    @Test
    void 같은_순번의_재게시도_현재_토큰을_덮어쓰지_않는다() {
        repository.saveIfNewer("first-login", 704L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        assertThat(repository.saveIfNewer("duplicate-login", 704L, Role.OWNER, true, Duration.ofMinutes(1), 1L)).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 704L)).contains(TokenHasher.sha256("first-login"));
    }

    @Test
    void 소비는_한번만_성공하고_게시전_활성포인터는_그대로다() {
        repository.saveIfNewer("consume-old", 710L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        assertThat(repository.consumeForRotation("consume-old", Role.OWNER).isSuccess()).isTrue();
        assertThat(repository.consumeForRotation("consume-old", Role.OWNER).isReuseDetected()).isTrue();
        assertThat(repository.findActiveHash(Role.OWNER, 710L)).contains(TokenHasher.sha256("consume-old"));
        assertThat(redis.getExpire("refreshToken:" + TokenHasher.sha256("consume-old"))).isBetween(1L, 60L);
    }

    @Test
    void 다른_역할의_토큰은_소비하지_않는다() {
        repository.saveIfNewer("admin-token", 711L, Role.ADMIN, true, Duration.ofMinutes(1), 1L);
        assertThat(repository.consumeForRotation("admin-token", Role.OWNER).data().role()).isEqualTo(Role.ADMIN);
        assertThat(repository.consumeForRotation("admin-token", Role.ADMIN).isSuccess()).isTrue();
    }

    @Test
    void DB_롤백후_소비표시를_복구하고_남은_TTL을_유지한다() {
        repository.saveIfNewer("rollback-old", 712L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        repository.consumeForRotation("rollback-old", Role.OWNER);
        repository.restoreConsumed(TokenHasher.sha256("rollback-old"));
        assertThat(repository.consumeForRotation("rollback-old", Role.OWNER).isSuccess()).isTrue();
        assertThat(redis.getExpire("refreshToken:" + TokenHasher.sha256("rollback-old"))).isBetween(1L, 60L);
    }

    @Test
    void 재발급_게시가_늦어도_나중_로그인을_덮어쓰지_않는다() {
        repository.saveIfNewer("refresh-base", 713L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        repository.consumeForRotation("refresh-base", Role.OWNER);
        repository.saveIfNewer("login-winner", 713L, Role.OWNER, true, Duration.ofMinutes(1), 3L);
        assertThat(repository.saveIfNewer("late-refresh", 713L, Role.OWNER, true, Duration.ofMinutes(1), 2L)).isFalse();
        assertThat(repository.findActiveHash(Role.OWNER, 713L)).contains(TokenHasher.sha256("login-winner"));
    }

    @Test
    void 재사용_폐기_순번이_이전_게시를_차단한다() {
        repository.saveIfNewer("revoked-current", 714L, Role.OWNER, true, Duration.ofMinutes(1), 2L);
        repository.revokeThroughVersion(Role.OWNER, 714L, TokenHasher.sha256("revoked-current"), 3L);
        assertThat(repository.find("revoked-current")).isEmpty();
        assertThat(repository.findActiveHash(Role.OWNER, 714L)).isEmpty();
        assertThat(repository.saveIfNewer("delayed-before-revoke", 714L, Role.OWNER, true, Duration.ofMinutes(1), 2L)).isFalse();
        assertThat(repository.saveIfNewer("login-after-revoke", 714L, Role.OWNER, true, Duration.ofMinutes(1), 4L)).isTrue();
    }

    @Test
    void 지연된_폐기는_더_최신_로그인을_삭제하지_않는다() {
        repository.saveIfNewer("latest-after-revoke", 715L, Role.OWNER, true, Duration.ofMinutes(1), 4L);
        repository.revokeThroughVersion(Role.OWNER, 715L, null, 3L);
        assertThat(repository.find("latest-after-revoke")).isPresent();
        assertThat(repository.findActiveHash(Role.OWNER, 715L)).contains(TokenHasher.sha256("latest-after-revoke"));
    }

    @Test
    void 동시_소비는_하나만_성공한다() throws Exception {
        repository.saveIfNewer("concurrent-refresh", 716L, Role.OWNER, true, Duration.ofMinutes(1), 1L);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<RefreshTokenRepository.RotateOutcome> task = () -> {
                start.await();
                return repository.consumeForRotation("concurrent-refresh", Role.OWNER);
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertThat(java.util.List.of(first.get().status(), second.get().status())).containsExactlyInAnyOrder(
                    RefreshTokenRepository.RotateOutcome.Status.SUCCESS, RefreshTokenRepository.RotateOutcome.Status.REUSE_DETECTED);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisConfig {
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
