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

    @Configuration(proxyBeanMethods = false)
    static class RedisConfig {
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
