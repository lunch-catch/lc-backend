package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.launchcatch.IntegrationTestSupport;
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

// 실제 DB 행 잠금, 커밋과 Valkey Lua를 함께 검증한다.
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class AdminTokenConcurrencyIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired AdminRepository admins;
    @Autowired AdminTokenService service;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Long id;
    private final String raw="a".repeat(43);

    @BeforeEach void setUp() {
        Admin admin=Admin.register("refresh."+System.nanoTime(),"password-hash","관리자",Role.ADMIN);
        admin.issueRefreshToken(TokenHasher.sha256(raw),LocalDateTime.now().plusDays(1));
        id=admins.saveAndFlush(admin).getId();
        tokens.saveIfNewer(raw,id,Role.ADMIN,true,Duration.ofDays(1),1L);
        doReturn(86400000L).when(jwt).refreshTokenValidityMs(Role.ADMIN);
        doReturn("access").when(jwt).createAccessToken(eq(id), eq(Role.ADMIN), anyMap());
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
            assertThat(tokens.findActiveHash(Role.ADMIN,id)).isEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void 성공_감사_저장실패후_기존_토큰은_캐시유무와_무관하게_다시_사용할_수_있다(boolean cacheLost) {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"))
                .when((com.launchcatch.ops.service.AuditLogWriterImpl) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit)).write(eq(id),eq("ADMIN_TOKEN_REISSUE"),any(),eq("result=SUCCESS"));
        assertThatThrownBy(()->service.reissue(raw)).isInstanceOf(AuthException.class);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isEqualTo(TokenHasher.sha256(raw));
        assertThat(tokens.findActiveHash(Role.ADMIN,id)).contains(TokenHasher.sha256(raw));
        var replacement = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(tokens).compareAndRotate(eq(raw), replacement.capture(), any());
        assertThat(tokens.find(replacement.getValue())).isEmpty();
        if (cacheLost) {
            tokens.revokeIfActiveHashMatches(TokenHasher.sha256(raw), Role.ADMIN, id);
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
        assertThat(tokens.findActiveHash(Role.ADMIN, id)).isEmpty();
        if (cacheLost) {
            tokens.revokeIfActiveHashMatches(TokenHasher.sha256(raw), Role.ADMIN, id);
        }
        assertThatThrownBy(() -> service.reissue(raw)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.reissue(replacement)).isInstanceOf(AuthException.class);
        assertThat(admins.findById(id).orElseThrow().getRefreshTokenHash()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where admin_id = ? and detail = ?",
                Long.class, id, "result=FAILURE;reason=REUSE_DETECTED")).isZero();
    }

    @Test void 재발급_Access_Token에_DB에서_승인한_발급_순번을_전달한다() {
        service.reissue(raw);
        verify(jwt).createAccessToken(eq(id), eq(Role.ADMIN),
                eq(com.launchcatch.admin.AdminAccessTokenClaims.forVersion(2L)));
    }

    @Test void 폐기_순번은_늦은_이전게시를_차단하고_새_로그인은_허용한다() {
        tokens.revokeBeforeVersion(id,Role.ADMIN,2L);
        assertThat(tokens.saveIfNewer("old",id,Role.ADMIN,true,Duration.ofDays(1),1L)).isFalse();
        assertThat(tokens.findActiveHash(Role.ADMIN,id)).isEmpty();
        assertThat(tokens.saveIfNewer("new",id,Role.ADMIN,true,Duration.ofDays(1),3L)).isTrue();
        tokens.revokeBeforeVersion(id,Role.ADMIN,2L);
        assertThat(tokens.findActiveHash(Role.ADMIN,id)).contains(TokenHasher.sha256("new"));
    }
}
