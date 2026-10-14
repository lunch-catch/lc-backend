package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.service.OwnerTutorialService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class OwnerTutorialIntegrationTest extends IntegrationTestSupport {
    private static final String PATH = "/v1/owner/me/tutorial";

    @Autowired MockMvc mvc;
    @Autowired OwnerRepository owners;
    @Autowired OwnerTutorialService service;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;

    private Owner owner(OwnerStatus status) {
        var owner = owners.saveAndFlush(Owner.create(UUID.randomUUID()+"@example.com", passwords.encode("password12")));
        jdbc.update("update owner set status=?, suspended_at=now(6), withdrawn_at=now(6) where owner_id=?",
                status.name(), owner.getId());
        return owner;
    }

    private Cookie token(Long id, Role role) {
        return new Cookie("accessToken", new TransactionTemplate(manager).execute(tx -> jwt.createAccessToken(id, role)));
    }

    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return token(1L, role);
        }
        // 관리자 JWT 검증이 계정 존재와 현재 역할을 확인하므로 유효한 계정을 준비한다.
        String loginId = UUID.randomUUID().toString();
        var now = java.time.LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, passwords.encode("password12"), "tutorial-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id=?", Long.class, loginId);
        return token(adminId, role);
    }

    @Test
    void 완료와_반복_요청은_200이며_다음_로그인에_반영된다() throws Exception {
        var owner = owner(OwnerStatus.ACTIVE);
        for (int i = 0; i < 2; i++) {
            mvc.perform(patch(PATH).cookie(token(owner.getId(), Role.OWNER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.tutorialViewed").value(true))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        var saved = owners.findById(owner.getId()).orElseThrow();
        assertThat(saved.isTutorialViewed()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(OwnerStatus.ACTIVE);
        assertThat(saved.getRefreshTokenIssuanceVersion()).isZero();
        assertThat(saved.getRefreshTokenHash()).isNull();
        mvc.perform(post("/v1/owner/auth/tokens").contentType("application/json")
                .content("{\"email\":\""+owner.getEmail()+"\",\"password\":\"password12\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tutorialViewed").value(true));
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아니면_403이다(OwnerStatus status) throws Exception {
        var owner = owner(status);
        mvc.perform(patch(PATH).cookie(token(owner.getId(), Role.OWNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER-002"));
        assertThat(owners.findById(owner.getId()).orElseThrow().isTutorialViewed()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void 다른_역할은_403이다(Role role) throws Exception {
        mvc.perform(patch(PATH).cookie(nonOwnerToken(role))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(patch(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 잘못된_토큰은_401이다() throws Exception {
        mvc.perform(patch(PATH).cookie(new Cookie("accessToken", "invalid")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 만료된_토큰은_401이다() throws Exception {
        var expired = new JwtTokenProvider("test-only-signing-secret-with-at-least-32-bytes", -1000,
                86400000, 1209600000, 1209600000, java.time.Clock.systemUTC());
        mvc.perform(patch(PATH).cookie(new Cookie("accessToken", expired.createAccessToken(1L, Role.OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 낡은_ACTIVE_캐시로는_정지된_점주를_허용하지_않는다() throws Exception {
        var owner = owner(OwnerStatus.SUSPENDED);
        redis.opsForValue().set("ownerStatus:"+owner.getId(), "ACTIVE", java.time.Duration.ofSeconds(30));
        mvc.perform(patch(PATH).cookie(token(owner.getId(), Role.OWNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER-002"));
    }

    @Test
    void 계정이_없으면_401이다() throws Exception {
        var legacy = new JwtTokenProvider("test-only-signing-secret-with-at-least-32-bytes", 1800000,
                86400000, 1209600000, 1209600000, java.time.Clock.systemUTC());
        mvc.perform(patch(PATH).cookie(new Cookie("accessToken", legacy.createAccessToken(Long.MAX_VALUE, Role.OWNER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void 다른_HTTP_메서드는_허용하지_않는다(String method) throws Exception {
        var owner = owner(OwnerStatus.ACTIVE);
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), PATH)
                .cookie(token(owner.getId(), Role.OWNER))).andExpect(status().isForbidden());
    }

    @Test
    void 롤백하면_완료_상태가_저장되지_않는다() {
        var owner = owner(OwnerStatus.ACTIVE);
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.complete(owner.getId());
            throw new IllegalStateException("rollback test");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(owners.findById(owner.getId()).orElseThrow().isTutorialViewed()).isFalse();
    }

    @Test
    void 동시_완료_요청은_모두_성공하고_완료_상태를_유지한다() throws Exception {
        var owner = owner(OwnerStatus.ACTIVE);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return service.complete(owner.getId()); });
            var b = pool.submit(() -> { start.await(); return service.complete(owner.getId()); });
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS).tutorialViewed()).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS).tutorialViewed()).isTrue();
        }
        assertThat(owners.findById(owner.getId()).orElseThrow().isTutorialViewed()).isTrue();
    }

    @Test
    void 정지_트랜잭션이_먼저_잠그면_완료_요청은_변경된_상태를_확인한다() throws Exception {
        var owner = owner(OwnerStatus.ACTIVE);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var suspension = pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                owners.findByIdForLogin(owner.getId()).orElseThrow();
                jdbc.update("update owner set status='SUSPENDED', suspended_at=now(6) where owner_id=?", owner.getId());
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var requested = new CountDownLatch(1);
                var completion = pool.submit(() -> {
                    requested.countDown();
                    assertThatThrownBy(() -> service.complete(owner.getId())).isInstanceOf(OwnerException.class);
                });
                assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> completion.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                suspension.get(10, TimeUnit.SECONDS);
                completion.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(owners.findById(owner.getId()).orElseThrow().isTutorialViewed()).isFalse();
    }
}
