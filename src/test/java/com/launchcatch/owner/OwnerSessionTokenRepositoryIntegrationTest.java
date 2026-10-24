package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.*;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class OwnerSessionTokenRepositoryIntegrationTest extends AbstractOwnerIntegrationTest {
    @Autowired StringRedisTemplate redis;

    @Test
    void 같은_점주의_두_세션은_독립적으로_게시하고_폐기한다() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String tokenA = UUID.randomUUID().toString();
        String tokenB = UUID.randomUUID().toString();
        sessionTokens.publish(tokenA, 7L, a, 1, Duration.ofDays(1));
        sessionTokens.publish(tokenB, 7L, b, 1, Duration.ofDays(1));
        sessionTokens.revoke(a, TokenHasher.sha256(tokenA), 2, Duration.ofDays(1));
        assertThat(sessionTokens.consume(tokenA)).isEmpty();
        assertThat(sessionTokens.consume(tokenB)).get().extracting(data -> data.sessionKey()).isEqualTo(b);
        assertThat(sessionTokens.isRevoked(a)).isTrue();
        assertThat(sessionTokens.isRevoked(b)).isFalse();
    }

    @Test
    void 처리중_표시를_복구하고_커밋후_소비를_확정한다() {
        String key = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        String hash = TokenHasher.sha256(token);
        sessionTokens.publish(token, 7L, key, 1, Duration.ofDays(1));
        assertThat(sessionTokens.consume(token)).get().extracting(data -> data.revoked()).isEqualTo(false);
        assertThat(redis.opsForValue().get("ownerSessionToken:" + hash)).endsWith("|PENDING");
        sessionTokens.restore(hash);
        assertThat(redis.opsForValue().get("ownerSessionToken:" + hash)).doesNotContain("|PENDING");
        sessionTokens.consume(token);
        sessionTokens.confirm(hash);
        assertThat(sessionTokens.consume(token)).get().extracting(data -> data.revoked()).isEqualTo(true);
    }

    @Test
    void 더_이른_게시와_폐기후_게시가_현재_토큰을_되살리지_못한다() {
        String key = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        String delayed = UUID.randomUUID().toString();
        sessionTokens.publish(newer, 7L, key, 3, Duration.ofDays(1));
        sessionTokens.publish(delayed, 7L, key, 2, Duration.ofDays(1));
        assertThat(sessionTokens.consume(delayed)).isEmpty();
        sessionTokens.revoke(key, TokenHasher.sha256(newer), 4, Duration.ofDays(1));
        sessionTokens.publish(newer, 7L, key, 3, Duration.ofDays(1));
        assertThat(sessionTokens.consume(newer)).isEmpty();
        assertThat(redis.getExpire("ownerSessionVersion:" + key)).isBetween(1L, 86400L);
        assertThat(redis.getExpire("ownerSessionRevoked:" + key)).isBetween(1L, 86400L);
    }

    @Test
    void 발급_순번은_long의_정밀도를_잃지_않고_비교한다() {
        String key = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        sessionTokens.publish(token, 7L, key, Long.MAX_VALUE - 1, Duration.ofDays(1));
        sessionTokens.revoke(key, TokenHasher.sha256(token), Long.MAX_VALUE, Duration.ofDays(1));
        sessionTokens.publish(token, 7L, key, Long.MAX_VALUE - 1, Duration.ofDays(1));
        assertThat(sessionTokens.consume(token)).isEmpty();
    }
}
