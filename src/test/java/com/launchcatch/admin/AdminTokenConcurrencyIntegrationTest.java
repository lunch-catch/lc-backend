package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.admin.service.AdminTokenService;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

// 실제 DB 행 잠금, 커밋과 Valkey Lua를 함께 검증한다.
@Testcontainers
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@SpringBootTest(properties="jwt.secret=test-only-secret-not-used-anywhere-else-0123456789abcdef")
class AdminTokenConcurrencyIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));
    @Container static final org.testcontainers.containers.GenericContainer<?> VALKEY =
            new org.testcontainers.containers.GenericContainer<>(DockerImageName.parse("valkey/valkey:9"))
                    .withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.data.redis.host",VALKEY::getHost);
        registry.add("spring.data.redis.port",()->VALKEY.getMappedPort(6379));
    }
    @Autowired AdminRepository admins;
    @Autowired AdminTokenService service;
    @MockitoSpyBean RefreshTokenRepository cache;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean JwtTokenProvider jwt;
    @MockitoSpyBean com.launchcatch.ops.service.AuditLogWriterImpl audit;
    private Long id;
    private final String raw="a".repeat(43);

    @BeforeEach void setUp() {
        Admin admin=Admin.register("refresh."+System.nanoTime(),"password-hash","관리자",Role.ADMIN);
        admin.issueRefreshToken(TokenHasher.sha256(raw),LocalDateTime.now().plusDays(1));
        id=admins.saveAndFlush(admin).getId();
        cache.saveIfNewer(raw,id,Role.ADMIN,true,Duration.ofDays(1),1L);
        when(jwt.refreshTokenValidityMs(Role.ADMIN)).thenReturn(86400000L);
        when(jwt.createAccessToken(id,Role.ADMIN)).thenReturn("access");
    }

    @Test void 동시_재발급은_최대_한번만_성공하고_재사용시_DB와_캐시를_폐기한다() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> request=()->{
                start.await(10,TimeUnit.SECONDS);
                try { service.reissue(raw); return true; }
                catch(AuthException failure) { return false; }
            };
            var first=workers.submit(request);
            var second=workers.submit(request);
            start.countDown();
            int successes=(first.get(20,TimeUnit.SECONDS)?1:0)+(second.get(20,TimeUnit.SECONDS)?1:0);
            assertThat(successes).isLessThanOrEqualTo(1);
            assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isNull();
            assertThat(cache.findActiveHash(Role.ADMIN,id)).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 성공_감사_저장실패후_기존_토큰은_캐시유무와_무관하게_다시_사용할_수_있다(boolean cacheLost) {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
                .when((com.launchcatch.ops.service.AuditLogWriterImpl) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).write(eq(id),eq("ADMIN_TOKEN_REISSUE"),any(),eq("result=SUCCESS"));
        assertThatThrownBy(()->service.reissue(raw)).isInstanceOf(AuthException.class);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(raw));
        assertThat(cache.findActiveHash(Role.ADMIN,id)).contains(TokenHasher.sha256(raw));
        var replacement = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache).compareAndRotate(eq(raw), replacement.capture(), any());
        assertThat(cache.find(replacement.getValue())).isEmpty();
        if (cacheLost) {
            cache.revokeIfActiveHashMatches(TokenHasher.sha256(raw), Role.ADMIN, id);
        }
        reset((com.launchcatch.ops.service.AuditLogWriterImpl) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit));
        var result = service.reissue(raw);
        assertThat(result.refreshToken()).isNotEqualTo(raw);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash())
                .isEqualTo(TokenHasher.sha256(result.refreshToken()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 재사용_감사장애에도_두_토큰은_폐기되고_실패는_운영로그에_남는다(boolean cacheLost, org.springframework.boot.test.system.CapturedOutput output) {
        String replacement = service.reissue(raw).refreshToken();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
                .when((com.launchcatch.ops.service.AuditLogWriterImpl)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit))
                .write(eq(id), eq("ADMIN_TOKEN_REISSUE"), any(), eq("result=FAILURE;reason=REUSE_DETECTED"));
        assertThatThrownBy(() -> service.reissue(raw)).isInstanceOfSatisfying(AuthException.class,
                failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(com.launchcatch.auth.exception.AuthErrorCode.REFRESH_TOKEN_REUSED));
        Admin revoked = admins.findById(id).orElseThrow();
        assertThat(revoked.getRefreshTokenHash()).isNull();
        assertThat(output.getAll()).contains("event=ADMIN_TOKEN_REISSUE_AUDIT_FAILED adminId=" + id
                + " reason=REUSE_DETECTED");
        assertThat(cache.findActiveHash(Role.ADMIN, id)).isEmpty();
        if (cacheLost) {
            cache.revokeIfActiveHashMatches(TokenHasher.sha256(raw), Role.ADMIN, id);
        }
        assertThatThrownBy(() -> service.reissue(raw)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.reissue(replacement)).isInstanceOf(AuthException.class);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where admin_id = ? and detail = ?",
                Long.class, id, "result=FAILURE;reason=REUSE_DETECTED")).isZero();
    }

    @Test void 폐기_순번은_늦은_이전게시를_차단하고_새_로그인은_허용한다() {
        cache.revokeBeforeVersion(id,Role.ADMIN,2L);
        assertThat(cache.saveIfNewer("old",id,Role.ADMIN,true,Duration.ofDays(1),1L)).isFalse();
        assertThat(cache.findActiveHash(Role.ADMIN,id)).isEmpty();
        assertThat(cache.saveIfNewer("new",id,Role.ADMIN,true,Duration.ofDays(1),3L)).isTrue();
        cache.revokeBeforeVersion(id,Role.ADMIN,2L);
        assertThat(cache.findActiveHash(Role.ADMIN,id)).contains(TokenHasher.sha256("new"));
    }
}
