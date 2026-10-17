package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
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
    private static final String BODY = "{}";

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
    }

    private Cookie access(Role role) {
        // 로그아웃 브랜치의 발급 순번 정책과도 호환되도록 점주 토큰은 같은 행 잠금 아래 생성한다.
        String token = new TransactionTemplate(manager).execute(tx -> {
            if (role == Role.OWNER) owners.findByIdForLogin(ownerId).orElseThrow();
            return jwt.createAccessToken(ownerId, role);
        });
        return new Cookie("accessToken", token);
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
        assertThat(agreements.findByOwnerId(ownerId)).hasValueSatisfying(row -> {
            assertThat(row.isRequired()).isTrue();
            assertThat(row.isAgreed()).isTrue();
            assertThat(row.getAgreedAt()).isNotNull();
            assertThat(row.getWithdrawnAt()).isNull();
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

    @Test
    void 인증_없이_요청하면_401이며_저장하지_않는다() throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void OWNER가_아닌_역할은_403이며_저장하지_않는다() throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"version\":0}", "{\"required\":true}", "{\"agreed\":false}"})
    void 허용하지_않는_요청_필드는_400이다(String body) throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void 이전_약관별_미동의_요청을_전체_동의로_처리하지_않는다() throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agreements\":[{\"agreed\":false}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void DB에_실제_INSERT한_뒤_실패해도_전체가_롤백된다() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("injected after flush");
        }).when(agreements).saveAndFlush(any());
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from store_registration_progress where owner_id = ?",
                Integer.class, ownerId)).isZero();
    }

    @Test
    void 동시_요청은_한_번만_저장한다() throws Exception {
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> submit = () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try { service.submit(ownerId); return "created"; }
                catch (StoreException e) { return e.getErrorCode().getCode(); }
            };
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "TERMS-001");
            assertThat(agreements.findByOwnerId(ownerId)).isPresent();
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
                try { service.submit(ownerId); return "created"; }
                catch (com.launchcatch.owner.exception.OwnerException e) { return e.getErrorCode().getCode(); }
            });
            release.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertThat(submit.get(10, TimeUnit.SECONDS)).isEqualTo("OWNER-002");
            assertThat(agreements.findByOwnerId(ownerId)).isEmpty();
        } finally {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test
    void 완료_후_재요청해도_동의와_진행_상태는_변경하지_않는다() throws Exception {
        service.submit(ownerId);
        var before = agreements.findByOwnerId(ownerId).orElseThrow();

        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TERMS-001"));

        var after = agreements.findByOwnerId(ownerId).orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getAgreedAt()).isEqualTo(before.getAgreedAt());
        assertThat(after.getWithdrawnAt()).isEqualTo(before.getWithdrawnAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.isAgreed()).isTrue();
        assertThat(after.isRequired()).isTrue();
        assertThat(jdbc.queryForObject("select version from store_registration_progress where owner_id = ?",
                Long.class, ownerId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select resume_step from store_registration_progress where owner_id = ?",
                String.class, ownerId)).isEqualTo("STORE_INFO");
    }

    @Test
    void DB_UNIQUE_충돌은_기존_동의를_보존하고_진행_상태_생성을_롤백한다() throws Exception {
        var existing = agreements.saveAndFlush(com.launchcatch.store.entity.OwnerTermsAgreement.record(
                ownerId, LocalDateTime.of(2026, 10, 1, 10, 0)));
        when(agreements.findByOwnerId(ownerId)).thenReturn(java.util.Optional.empty());

        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TERMS-001"));

        assertThat(jdbc.queryForObject("select count(*) from owner_terms_agreement where owner_id = ?",
                Integer.class, ownerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select agreed_at from owner_terms_agreement where owner_id = ?",
                LocalDateTime.class, ownerId)).isEqualTo(existing.getAgreedAt());
        assertThat(jdbc.queryForObject("select count(*) from store_registration_progress where owner_id = ?",
                Integer.class, ownerId)).isZero();
    }
    @Test
    void 요청_본문과_버전_없이_최초_동의할_수_있다() throws Exception {
        mvc.perform(put("/v1/owner/terms-agreements").cookie(access(Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredTermsCompleted").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.resumeStep").value("STORE_INFO"));
        assertThat(agreements.findByOwnerId(ownerId)).isPresent();
    }
}
