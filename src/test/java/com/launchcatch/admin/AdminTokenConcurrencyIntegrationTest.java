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
    @Autowired RefreshTokenRepository cache;
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

    @Test void 성공_감사_저장실패는_DB회전을_롤백하고_새_캐시를_보상한다() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
                .when((com.launchcatch.ops.service.AuditLogWriterImpl) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).write(eq(id),eq("ADMIN_TOKEN_REISSUE"),any(),eq("result=SUCCESS"));
        assertThatThrownBy(()->service.reissue(raw)).isInstanceOf(AuthException.class);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(raw));
        assertThat(cache.findActiveHash(Role.ADMIN,id)).isEmpty();
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
