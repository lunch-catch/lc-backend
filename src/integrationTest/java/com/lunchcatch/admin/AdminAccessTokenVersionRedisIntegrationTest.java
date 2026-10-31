package com.lunchcatch.admin;

import com.lunchcatch.IntegrationTestSupport;
import com.lunchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.lunchcatch.admin.service.AdminAccessTokenVerifier;
import com.lunchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.lunchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.lunchcatch.auth.jwt.JwtAuthenticationFilter;
import com.lunchcatch.auth.jwt.JwtTokenProvider;

import static org.assertj.core.api.Assertions.*;
import com.lunchcatch.auth.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class AdminAccessTokenVersionRedisIntegrationTest extends IntegrationTestSupport {

    @Test
    void 폐기순번은_만료되지_않고_역순_게시와_BIGINT_정밀도_손실을_막는다() {
        var connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connection.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(connection);
            var repository = new AdminAccessTokenVersionRepository(redis);
            repository.invalidateThroughVersion(Role.ADMIN, 1L, 11L);
            repository.invalidateThroughVersion(Role.ADMIN, 1L, 9L);
            assertThat(repository.isVersionValid(Role.ADMIN, 1L, 10L)).isFalse();
            assertThat(repository.isVersionValid(Role.ADMIN, 1L, 11L)).isFalse();
            assertThat(repository.isVersionValid(Role.ADMIN, 1L, 12L)).isTrue();
            assertThat(redis.getExpire("accessTokenRevokedVersion:ADMIN:1")).isEqualTo(-1L);
            repository.invalidateThroughVersion(Role.SUPER_ADMIN, 1L, Long.MAX_VALUE);
            repository.invalidateThroughVersion(Role.SUPER_ADMIN, 1L, Long.MAX_VALUE - 1);
            assertThat(repository.isVersionValid(Role.SUPER_ADMIN, 1L, Long.MAX_VALUE)).isFalse();
            assertThat(redis.opsForValue().get("accessTokenRevokedVersion:SUPER_ADMIN:1"))
                    .isEqualTo(Long.toString(Long.MAX_VALUE));
        } finally { connection.destroy(); }
    }

    @Test
    void Redis_폐기키를_삭제해도_DB가_이전_토큰을_차단한다() {
        var connection = new LettuceConnectionFactory(VALKEY.getHost(), VALKEY.getMappedPort(6379));
        connection.afterPropertiesSet();
        try {
            var redis = new StringRedisTemplate(connection);
            var versions = new AdminAccessTokenVersionRepository(redis);
            var database = org.mockito.Mockito.mock(com.lunchcatch.admin.repository.AdminAccessTokenRevocationRepository.class);
            var cutoff = java.time.LocalDateTime.of(2026, 10, 12, 12, 0);
            org.mockito.Mockito.when(database.findRevocation(2L)).thenReturn(java.util.Optional.of(
                    new AdminAccessTokenRevocation(11L, cutoff)));
            var verifier = new AdminAccessTokenVerifier(versions,
                    new AccessTokenCutoffVerifier(new AccessTokenValidAfterRepository(redis)), database, org.mockito.Mockito.mock(com.lunchcatch.admin.service.AdminSessionService.class));
            versions.invalidateThroughVersion(Role.ADMIN, 2L, 11L);
            redis.delete(java.util.List.of("accessTokenRevokedVersion:ADMIN:2", "accessTokenValidAfter:ADMIN:2"));
            assertThat(versions.isVersionValid(Role.ADMIN, 2L, 10L)).isTrue();
            assertThat(verifier.isValidAfter(Role.ADMIN, 2L, cutoff.minusSeconds(1), 10L,
                    com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
            assertThat(verifier.isValidAfter(Role.ADMIN, 2L, cutoff.minusSeconds(1), null,
                    com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isFalse();
            assertThat(verifier.isValidAfter(Role.ADMIN, 2L, cutoff, 12L,
                    com.lunchcatch.auth.jwt.CutoffPolicy.LENIENT)).isTrue();
        } finally { connection.destroy(); }
    }
}
