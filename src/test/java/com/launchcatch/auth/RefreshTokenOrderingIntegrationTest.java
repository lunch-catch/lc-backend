package com.launchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @Autowired
    private AccessTokenValidAfterRepository cutoff;

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

    @ParameterizedTest
    @EnumSource(Role.class)
    void 늦게_도착한_이전_AT차단은_최신_차단을_되돌리지_않는다(Role role) {
        LocalDateTime later = LocalDateTime.of(2026, 10, 10, 12, 0);
        cutoff.invalidateBefore(role, 718L, later, Duration.ofMinutes(30));
        cutoff.invalidateBefore(role, 718L, later.minusMinutes(1), Duration.ofMinutes(1));
        assertThat(cutoff.isValidAfter(role, 718L, later.minusSeconds(1))).isFalse();
        assertThat(cutoff.isValidAfter(role, 718L, later)).isTrue();
        assertThat(redis.getExpire("accessTokenValidAfter:" + role.name() + ":718")).isGreaterThan(1700);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void 같은_차단시각의_재시도도_기존_TTL을_줄이지_않는다(Role role) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0);
        cutoff.invalidateBefore(role, 719L, now, Duration.ofMinutes(30));
        cutoff.invalidateBefore(role, 719L, now, Duration.ofMinutes(1));
        assertThat(redis.getExpire("accessTokenValidAfter:" + role.name() + ":719")).isGreaterThan(1700);
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
        AccessTokenValidAfterRepository cutoff(StringRedisTemplate redisTemplate) {
            return new AccessTokenValidAfterRepository(redisTemplate);
        }

        @Bean
        RefreshTokenRepository refreshTokenRepository(StringRedisTemplate redisTemplate) {
            return new RefreshTokenRepository(redisTemplate);
        }
    }
}
