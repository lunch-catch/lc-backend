package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.dto.OwnerTermsAgreementRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.owner.service.OwnerTermsSubmissionService;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

class OwnerTermsAgreementIntegrationTest extends AbstractStoreIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private OwnerRepository owners;
    @Autowired private OwnerTermsSubmissionService service;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private MockMvc mvc;
    private Long ownerId;
    private static final String BODY = """
            {"version":0,"agreements":[{"termsType":"SERVICE","termsVersion":"1.0","agreed":true},
            {"termsType":"MARKETING","termsVersion":"1.0","agreed":false}]}
            """;

    @BeforeEach
    void setUp() {
        Owner owner = Owner.create("owner-terms@example.com", "hash");
        var now = LocalDateTime.now();
        owner.recordLogin("a".repeat(64), now.plusDays(14), now);
        ownerId = owners.saveAndFlush(owner).getId();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        reset(agreements);
        jdbc.update("delete from owner_terms_agreement where owner_id = ?", ownerId);
        jdbc.update("delete from store_registration_progress where owner_id = ?", ownerId);
        owners.deleteById(ownerId);
        jdbc.update("delete from admin where login_id = ?", "owner-terms-admin");
    }

    private Cookie access(Role role) {
        // 로그아웃 브랜치의 발급 순번 정책과도 호환되도록 점주 토큰은 같은 행 잠금 아래 생성한다.
        String token = new TransactionTemplate(manager).execute(tx -> {
            if (role == Role.OWNER) owners.findByIdForLogin(ownerId).orElseThrow();
            return jwt.createAccessToken(ownerId, role);
        });
        return new Cookie("accessToken", token);
    }

    private OwnerTermsAgreementRequest request() {
        return new OwnerTermsAgreementRequest(0L, List.of(
                new OwnerTermsAgreementRequest.Agreement("SERVICE", "1.0", true),
                new OwnerTermsAgreementRequest.Agreement("MARKETING", "1.0", false)));
    }

    @Test
    void 실제_인증과_DB_저장을_거쳐_200을_반환하고_점주_토큰을_유지한다() throws Exception {
        Owner before = owners.findById(ownerId).orElseThrow();
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requiredTermsCompleted").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.resumeStep").value("STORE_INFO"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(agreements.findAllByOwnerId(ownerId)).hasSize(2).allSatisfy(row -> {
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getUpdatedAt()).isNotNull();
        });
        Owner after = owners.findById(ownerId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OwnerStatus.ONBOARDING);
        assertThat(after.isTutorialViewed()).isEqualTo(before.isTutorialViewed());
        assertThat(after.getRefreshTokenHash()).isEqualTo(before.getRefreshTokenHash());
        assertThat(after.getRefreshTokenIssuanceVersion()).isEqualTo(before.getRefreshTokenIssuanceVersion());
        assertThat(after.getRefreshTokenExpiresAt()).isEqualTo(before.getRefreshTokenExpiresAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "broken", "expired"})
    void 인증_실패는_AUTH005이며_저장하지_않는다(String value) throws Exception {
        var builder = put("/v1/owner/terms-agreements").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (value.equals("broken")) builder.cookie(new Cookie("accessToken", "invalid"));
        if (value.equals("expired")) {
            var oldClock = java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofHours(-2));
            var oldJwt = new JwtTokenProvider("test-only-secret-not-used-anywhere-else-0123456789abcdef",
                    1800000, 86400000, 1209600000, 1209600000, oldClock);
            builder.cookie(new Cookie("accessToken", oldJwt.createAccessToken(ownerId, Role.OWNER)));
        }
        mvc.perform(builder).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 다른_역할은_AUTH006이다(Role role) throws Exception {
        if (role == Role.ADMIN || role == Role.SUPER_ADMIN) {
            jdbc.update("""
                    insert into admin (admin_id, login_id, password_hash, name, role, status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """, ownerId, "owner-terms-admin", "hash", "약관 테스트 관리자", role.name(),
                    LocalDateTime.now(), LocalDateTime.now());
        }
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"agreements\":[]}", "{\"agreements\":[null]}",
            "{\"agreements\":[{\"termsType\":\"SERVICE\",\"termsVersion\":\"1.0\"}]}",
            "{\"agreements\":[{\"termsType\":\" \",\"termsVersion\":\"1.0\",\"agreed\":true}]}"})
    void 누락과_잘못된_입력은_400이다(String body) throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void 필수_미동의는_422이며_선택_내역도_저장하지_않는다() throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("true", "false")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STORE-006"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void 같은_요청을_반복하면_409이다() throws Exception {
        service.submit(ownerId, request());
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE-001"));
        assertThat(agreements.findAllByOwnerId(ownerId)).hasSize(2);
    }

    @Test
    void DB에_실제_INSERT한_뒤_실패해도_전체가_롤백된다() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("injected after flush");
        }).when(agreements).saveAllAndFlush(anyList());
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from store_registration_progress where owner_id = ?",
                Integer.class, ownerId)).isZero();
    }

    @Test
    void 최신_버전으로_선택_동의를_수정해도_재개_위치를_유지한다() throws Exception {
        service.submit(ownerId, request());
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"version\":0", "\"version\":1")
                                .replace("\"agreed\":false", "\"agreed\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.resumeStep").value("STORE_INFO"));
        assertThat(agreements.findAllByOwnerId(ownerId)).hasSize(2).allMatch(row -> row.isAgreed());
    }

    @Test
    void 동시_요청은_한_번만_저장한다() throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> submit = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try { service.submit(ownerId, request()); return "created"; }
                catch (StoreException e) { return e.getErrorCode().getCode(); }
            };
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "STORE-001");
            assertThat(agreements.findAllByOwnerId(ownerId)).hasSize(2);
        } finally {
            start.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 동시_상태_변경이_커밋된_후_현재_상태로_거부한다() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var change = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                var owner = owners.findByIdForLogin(ownerId).orElseThrow();
                ReflectionTestUtils.setField(owner, "status", OwnerStatus.ACTIVE);
                owners.saveAndFlush(owner);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var submit = workers.submit(() -> {
                try { service.submit(ownerId, request()); return "created"; }
                catch (com.launchcatch.owner.exception.OwnerException e) { return e.getErrorCode().getCode(); }
            });
            release.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertThat(submit.get(10, TimeUnit.SECONDS)).isEqualTo("OWNER-002");
            assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test
    void DB_UNIQUE_충돌은_기존_동의와_진행_버전을_보존한다() throws Exception {
        service.submit(ownerId, request());
        when(agreements.findAllByOwnerId(ownerId)).thenReturn(List.of());
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"version\":0", "\"version\":1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE-005"));
        assertThat(jdbc.queryForObject("select count(*) from owner_terms_agreement where owner_id = ?",
                Integer.class, ownerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id = ?",
                Long.class, ownerId)).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 현재_DB_상태가_온보딩이_아니면_403이다(OwnerStatus ownerStatus) throws Exception {
        jdbc.update("update owner set status = ?, suspended_at = ?, withdrawn_at = ? where owner_id = ?",
                ownerStatus.name(), ownerStatus == OwnerStatus.SUSPENDED ? LocalDateTime.now() : null,
                ownerStatus == OwnerStatus.WITHDRAWN ? LocalDateTime.now() : null, ownerId);
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER-002"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

}
