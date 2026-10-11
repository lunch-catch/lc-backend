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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "jwt.secret=test-only-signing-secret-for-owner-terms-agreements-0123456789",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "kakao.app-id=test", "kakao.admin-key=test", "billing.toss.secret-key=test_sk_test",
        "store.owner-terms.policies[0].terms-type=SERVICE",
        "store.owner-terms.policies[0].terms-version=1.0",
        "store.owner-terms.policies[0].required=true",
        "store.owner-terms.policies[1].terms-type=MARKETING",
        "store.owner-terms.policies[1].terms-version=1.0",
        "store.owner-terms.policies[1].required=false"})
class OwnerTermsAgreementIntegrationTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));
    @Container static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired private WebApplicationContext context;
    @Autowired private OwnerRepository owners;
    @MockitoSpyBean private OwnerTermsAgreementRepository agreements;
    @Autowired private OwnerTermsSubmissionService service;
    @Autowired private JwtTokenProvider jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private MockMvc mvc;
    private Long ownerId;
    private static final String BODY = """
            {"agreements":[{"termsType":"SERVICE","termsVersion":"1.0","agreed":true},
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

    private OwnerTermsAgreementRequest request() {
        return new OwnerTermsAgreementRequest(List.of(
                new OwnerTermsAgreementRequest.Agreement("SERVICE", "1.0", true),
                new OwnerTermsAgreementRequest.Agreement("MARKETING", "1.0", false)));
    }

    @Test
    void 실제_인증과_DB_저장을_거쳐_201을_반환하고_점주_토큰을_유지한다() throws Exception {
        Owner before = owners.findById(ownerId).orElseThrow();
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.requiredTermsCompleted").value(true))
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
        var builder = post("/v1/owner/terms-agreements").contentType(MediaType.APPLICATION_JSON).content(BODY);
        if (value.equals("broken")) builder.cookie(new Cookie("accessToken", "invalid"));
        if (value.equals("expired")) {
            var oldClock = java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofHours(-2));
            var oldJwt = new JwtTokenProvider("test-only-signing-secret-for-owner-terms-agreements-0123456789",
                    1800000, 86400000, 1209600000, 1209600000, oldClock);
            builder.cookie(new Cookie("accessToken", oldJwt.createAccessToken(ownerId, Role.OWNER)));
        }
        mvc.perform(builder).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 다른_역할은_AUTH006이다(Role role) throws Exception {
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"agreements\":[]}", "{\"agreements\":[null]}",
            "{\"agreements\":[{\"termsType\":\"SERVICE\",\"termsVersion\":\"1.0\"}]}",
            "{\"agreements\":[{\"termsType\":\" \",\"termsVersion\":\"1.0\",\"agreed\":true}]}"})
    void 누락과_잘못된_입력은_400이다(String body) throws Exception {
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void 필수_미동의는_422이며_선택_내역도_저장하지_않는다() throws Exception {
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("true", "false")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("STORE-002"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

    @Test
    void 같은_요청을_반복하면_409이다() throws Exception {
        service.submit(ownerId, request());
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE-001"));
        assertThat(agreements.findAllByOwnerId(ownerId)).hasSize(2);
    }

    @Test
    void DB에_실제_INSERT한_뒤_실패해도_전체가_롤백된다() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("injected after flush");
        }).when(agreements).saveAllAndFlush(anyList());
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
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
    void MySQL의_실제_UNIQUE_충돌도_409이며_기존_내역을_보존한다() throws Exception {
        service.submit(ownerId, request());
        // 조회로 중복을 찾지 못하는 경우에도 실제 DB 제약의 최종 방어가 동작해야 한다.
        when(agreements.findAllByOwnerId(ownerId)).thenReturn(List.of());
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE-001"));
        assertThat(jdbc.queryForObject("select count(*) from owner_terms_agreement where owner_id = ?",
                Integer.class, ownerId)).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 현재_DB_상태가_온보딩이_아니면_403이다(OwnerStatus ownerStatus) throws Exception {
        jdbc.update("update owner set status = ?, suspended_at = ?, withdrawn_at = ? where owner_id = ?",
                ownerStatus.name(), ownerStatus == OwnerStatus.SUSPENDED ? LocalDateTime.now() : null,
                ownerStatus == OwnerStatus.WITHDRAWN ? LocalDateTime.now() : null, ownerId);
        mvc.perform(post("/v1/owner/terms-agreements").cookie(access(Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER-002"));
        assertThat(agreements.findAllByOwnerId(ownerId)).isEmpty();
    }

}
