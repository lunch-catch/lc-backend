package com.launchcatch.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
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

// 중단 표시 갱신 스크립트를 실제 Valkey 9 에서 검증한다
@Testcontainers
@SpringBootTest(classes = DailySpendHashRepositoryIntegrationTest.RedisConfig.class)
class DailySpendHashRepositoryIntegrationTest {

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:9")
            .withExposedPorts(6379);

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 10, 0, 0, 100_000);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 1, 10, 0, 0, 200_000);

    @Autowired
    private DailySpendHashRepository repository;

    @Autowired
    private StringRedisTemplate redis;

    // 00:00 예약이 만들 해시를 흉내 낸다
    private String seedHash(Long campaignId) {
        String key = DailySpendHashRepository.key(DAY, campaignId);
        redis.opsForHash().putAll(key, Map.of("reserved", "10000", "spend", "0", "paused", "0"));
        return key;
    }

    @Test
    void 해시가_있으면_중단_표시와_시각을_쓰고_다른_필드는_그대로_둔다() {
        String key = seedHash(101L);

        assertThat(repository.applyStatus(DAY, 101L, true, T1)).isTrue();

        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("1");
        assertThat(redis.opsForHash().get(key, "changedAt"))
                .isEqualTo(String.valueOf(DailySpendHashRepository.epochMicros(T1)));
        assertThat(redis.opsForHash().get(key, "reserved")).isEqualTo("10000");
    }

    @Test
    void 해시가_없으면_만들지_않는다() {
        assertThat(repository.applyStatus(DAY, 102L, true, T1)).isFalse();

        assertThat(redis.hasKey(DailySpendHashRepository.key(DAY, 102L))).isFalse();
    }

    @Test
    void 늦은_변경이_먼저_반영되면_이른_변경과_같은_변경은_무시한다() {
        String key = seedHash(103L);

        assertThat(repository.applyStatus(DAY, 103L, false, T2)).isTrue();
        assertThat(repository.applyStatus(DAY, 103L, true, T1)).isFalse();
        assertThat(repository.applyStatus(DAY, 103L, true, T2)).isFalse();

        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("0");
    }

    @Test
    void 이른_변경_뒤의_늦은_변경은_반영한다() {
        String key = seedHash(104L);
        repository.applyStatus(DAY, 104L, true, T1);

        assertThat(repository.applyStatus(DAY, 104L, false, T2)).isTrue();
        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("0");
    }

    @Test
    void 해시를_지우면_다음_갱신은_해시를_만들지_않는다() {
        seedHash(105L);

        repository.evict(DAY, 105L);

        assertThat(redis.hasKey(DailySpendHashRepository.key(DAY, 105L))).isFalse();
        assertThat(repository.applyStatus(DAY, 105L, true, T1)).isFalse();
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
        DailySpendHashRepository dailySpendHashRepository(StringRedisTemplate redisTemplate) {
            return new DailySpendHashRepository(redisTemplate);
        }
    }
}
