package com.launchcatch.adserving.feed.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// 창을 세는 Lua 스크립트를 실제 Valkey 에서 돌린다. 컨텍스트는 띄우지 않는다
class FeedRateLimitRepositoryTest extends IntegrationTestSupport {

    private static final Long MEMBER_ID = 3001L;
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Instant START = Instant.parse("2026-10-15T01:15:00Z");

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private FeedRateLimitRepository repository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.delete("feedRateLimit:" + MEMBER_ID);
        repository = new FeedRateLimitRepository(redis);
    }

    @AfterEach
    void tearDown() {
        redis.delete("feedRateLimit:" + MEMBER_ID);
        connectionFactory.destroy();
    }

    @Test
    void 최근_60초에_6회까지_받고_일곱째는_넣지_않고_거부한다() {
        for (int i = 0; i < 6; i++) {
            RateLimitResult result = repository.tryAcquire(MEMBER_ID, START.plusSeconds(i), 6, WINDOW);
            assertThat(result.allowed()).isTrue();
            assertThat(result.count()).isEqualTo(i + 1);
        }

        RateLimitResult seventh = repository.tryAcquire(MEMBER_ID, START.plusSeconds(10), 6, WINDOW);

        assertThat(seventh.allowed()).isFalse();
        assertThat(seventh.count()).isEqualTo(6);
        assertThat(seventh.oldest()).isEqualTo(START);
        assertThat(redis.opsForZSet().size("feedRateLimit:" + MEMBER_ID)).isEqualTo(6);
    }

    @Test
    void 가장_오래된_요청이_60초를_지나면_창에서_빠져_다시_받는다() {
        for (int i = 0; i < 6; i++) {
            repository.tryAcquire(MEMBER_ID, START.plusSeconds(i), 6, WINDOW);
        }

        RateLimitResult result = repository.tryAcquire(MEMBER_ID, START.plusSeconds(60), 6, WINDOW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.count()).isEqualTo(6);
        assertThat(result.oldest()).isEqualTo(START.plusSeconds(1));
    }
}
