package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.admin.config.AdminLoginAuditFilter;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.global.exception.GlobalExceptionHandler;
import com.launchcatch.ops.contract.AuditLogWriter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/*
 * 관리자 로그인과 로그아웃과 재발급의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 에 @ComponentScan 정규식 필터까지 써서 컨트롤러와 서비스만
 * 골라 담고 저장소와 트랜잭션 매니저를 전부 목으로 바꾼 슬라이스였다. 그 모양은 통합 테스트가
 * 아니고(구현 플로우 5단계) 컨텍스트를 하나 더 띄웠다(#278).
 *
 * 계정은 실제 행으로 둔다. 목을 쓰던 때는 그 계정으로 로그인이 끝까지 가는지를 목의 응답으로
 * 꾸며 보고 있었다. 장애를 만들어야 하는 자리만 기반 클래스의 스파이로 스텁한다. 스파이에는
 * when(...) 이 실제 메서드를 먼저 부르므로 doReturn 과 doThrow 와 doAnswer 로 스텁한다.
 */
class AdminLoginSecurityIntegrationTest extends AbstractAdminIntegrationTest {

    private static final String LOGIN_PATH = "/v1/admin/auth/tokens";
    private static final String REFRESH_PATH = "/v1/admin/auth/tokens:refresh";
    private static final String PASSWORD = "Freshman!2026";
    private static final String REFRESH_TOKEN = "a".repeat(43);

    /*
     * 로그인과 재발급 경로는 IP 당 분당 10회로 막혀 있다(AuthRateLimitFilter). 보안 설정만 담던
     * 슬라이스에는 그 필터가 없었는데 전체를 띄우면 체인에 들어온다. 한 클래스의 요청이 같은 IP
     * 로 쌓이면 뒤에 도는 테스트가 429 를 받으므로 테스트마다 다른 IP 로 보낸다.
     */
    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> created = new ArrayList<>();
    private String clientIp;
    private String loginId;
    private Long adminId;
    private String body;

    @BeforeEach
    void setUp() {
        reset(admins, tokens, transactions, versions, legacyCutoff, revocations, audit, encoder, jwt);
        clientIp = "192.0.2." + (CLIENTS.incrementAndGet() % 200 + 1);
        loginId = "login." + UUID.randomUUID().toString().substring(0, 8);
        adminId = insertAdmin(loginId, PASSWORD, Role.SUPER_ADMIN);
        body = requestBody(loginId, PASSWORD);
    }

    @AfterEach
    void tearDown() {
        created.forEach(id -> jdbc.update("delete from admin where admin_id = ?", id));
    }

    private Long insertAdmin(String id, String password, Role role) {
        Admin admin = admins.saveAndFlush(Admin.register(id, encoder.encode(password), "관리자", role));
        created.add(admin.getId());
        return admin.getId();
    }

    private String requestBody(String id, String password) {
        return "{\"loginId\":\"" + id + "\",\"password\":\"" + password + "\"}";
    }

    private MockHttpServletRequestBuilder login(String json) {
        return post(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).content(json).with(fromClient());
    }

    private MockHttpServletRequestBuilder refresh() {
        return post(REFRESH_PATH).with(fromClient());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor fromClient() {
        return request -> {
            request.setRemoteAddr(clientIp);
            return request;
        };
    }

    // 발급 순번을 넣은 관리자 AT 다. 로그인과 재발급 서비스만 이 클레임을 넣는다.
    private Cookie accessToken(Long id, Role role) {
        return new Cookie("accessToken", jwt.createAccessToken(id, role, AdminAccessTokenClaims.forVersion(1L)));
    }

    /*
     * DB 백업만 있는 상태를 만든다. 발급 순번을 1 로 올려 두어야 로그아웃 폐기 상한이 0 인
     * 계정과 구별되고, admin 의 CHECK 제약도 지켜진다.
     */
    private void backUpRefreshToken(Long id) {
        jdbc.update("update admin set refresh_token_hash = ?, refresh_token_expires_at = ?, "
                        + "refresh_token_issuance_version = 1 where admin_id = ?",
                TokenHasher.sha256(REFRESH_TOKEN), LocalDateTime.now().plusDays(1), id);
    }

    private ListAppender<ILoggingEvent> attach(Class<?> target) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(target)).addAppender(appender);
        return appender;
    }

    private void detach(Class<?> target, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(target)).detachAppender(appender);
        appender.stop();
    }

    @Test
    void 인증없이_로그인하고_실제_JWT와_명세의_응답을_받는다() throws Exception {
        var result = mvc.perform(login(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.adminId").value(adminId.intValue()))
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        Cookie access = result.getResponse().getCookie("accessToken");
        Cookie refresh = result.getResponse().getCookie("refreshToken");
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(access.getMaxAge()).isEqualTo(1800);
        assertThat(jwt.validateToken(access.getValue())).isTrue();
        assertThat(jwt.getRole(access.getValue())).isEqualTo(Role.SUPER_ADMIN);
        assertThat(jwt.getIssuedAt(access.getValue())).isNotNull();
        assertThat(refresh.getPath()).isEqualTo("/v1/admin/auth/");
        assertThat(refresh.getMaxAge()).isEqualTo(86400);
    }

    @Test
    void 없는_계정은_AUTH001이며_쿠키를_발급하지_않는다() throws Exception {
        mvc.perform(login(requestBody("login.absent", PASSWORD)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @CsvSource({"73, 0", "1, 24", "0, 25"})
    void UTF8_72바이트_초과_비밀번호는_401_AUTH001이며_쿠키를_발급하지_않는다(
            int asciiLength, int koreanLength) throws Exception {
        String password = "a".repeat(asciiLength) + "가".repeat(koreanLength);
        mvc.perform(login(requestBody(loginId, password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(tokens);
        assertThat(admins.findByLoginId(loginId).orElseThrow().getRefreshTokenHash()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"72, 0", "0, 24"})
    void UTF8_72바이트_비밀번호는_일치하면_로그인할_수_있다(
            int asciiLength, int koreanLength) throws Exception {
        String password = "a".repeat(asciiLength) + "가".repeat(koreanLength);
        String id = "login." + UUID.randomUUID().toString().substring(0, 8);
        insertAdmin(id, password, Role.SUPER_ADMIN);
        mvc.perform(login(requestBody(id, password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 필수_값이_없으면_400이며_쿠키를_발급하지_않는다() throws Exception {
        mvc.perform(login("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void DB_백업_장애는_503_AUTH002_RetryAfter_ERROR이며_쿠키를_발급하지_않는다() throws Exception {
        doThrow(new DataAccessResourceFailureException("DB unavailable"))
                .when(admins).findByIdForUpdate(adminId);
        var appender = attach(GlobalExceptionHandler.class);
        try {
            mvc.perform(login(body))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
        } finally {
            detach(GlobalExceptionHandler.class, appender);
        }
    }

    @Test
    void Redis_저장_장애시_DB_백업후_로그인_성공을_유지한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(tokens).saveIfNewer(any(), eq(adminId), eq(Role.SUPER_ADMIN), eq(true), any(), anyLong());
        mvc.perform(login(body))
                .andExpect(status().isOk()).andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/admin/auth/unknown"})
    void 아직_구현하지_않은_인증_경로는_유효한_토큰으로도_접근할_수_없다(String path) throws Exception {
        mvc.perform(post(path).cookie(accessToken(adminId, Role.SUPER_ADMIN)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 유효한_관리자는_Refresh쿠키_없이_로그아웃한다(Role role) throws Exception {
        String id = "login." + UUID.randomUUID().toString().substring(0, 8);
        Long logoutId = insertAdmin(id, PASSWORD, role);
        var result = mvc.perform(delete(LOGIN_PATH).cookie(accessToken(logoutId, role)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getCookie("accessToken").getMaxAge()).isZero();
        assertThat(result.getResponse().getCookie("refreshToken").getPath()).isEqualTo("/v1/admin/auth/");
        verify(audit).write(logoutId, "ADMIN_LOGOUT", String.valueOf(logoutId), "result=SUCCESS");
    }

    @Test
    void 로그아웃_인증누락은_AUTH005다() throws Exception {
        mvc.perform(delete(LOGIN_PATH))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 폐기된_Access_Token은_로그아웃에_재사용할_수_없다() throws Exception {
        doReturn(false).when(versions).isVersionValid(Role.SUPER_ADMIN, adminId, 1L);
        mvc.perform(delete(LOGIN_PATH).cookie(accessToken(adminId, Role.SUPER_ADMIN)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    /*
     * 사용자 토큰은 역할을 담당하는 정책이 없어 발급 시각 기준으로만 검증한다. 점주 토큰은
     * 점주 정책이 계정 행의 폐기 순번을 읽으므로 그 계정을 실제로 만들어 둔다. 둘 다 인증은
     * 통과하고 관리자 체인의 권한에서 막히는 것을 본다.
     */
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "OWNER"})
    void 관리자_외_역할은_로그아웃할_수_없다(Role role) throws Exception {
        Long id = role == Role.OWNER ? insertOwner() : 1L;
        mvc.perform(delete(LOGIN_PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(id, role))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    private Long insertOwner() {
        String email = UUID.randomUUID() + "@example.com";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into owner (email, password_hash, created_at, updated_at) values (?, ?, ?, ?)",
                email, encoder.encode(PASSWORD), now, now);
        return jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, email);
    }

    @Test
    void 로그아웃_저장소_장애는_503이며_쿠키를_삭제하지_않는다() throws Exception {
        doThrow(new DataAccessResourceFailureException("token-must-not-leak"))
                .when(tokens).revokeBeforeVersion(eq(adminId), any(), anyLong());
        mvc.perform(delete(LOGIN_PATH).cookie(accessToken(adminId, Role.SUPER_ADMIN)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /*
     * 감사 기록은 트랜잭션 프록시 뒤에 있다. 프록시를 거쳐 스텁하면 적어 둔 매처가 그 프록시가
     * 먼저 부르는 트랜잭션 매니저 스파이의 호출에 먹혀서 인수 수가 어긋난다. 프록시 안의
     * 스파이에 직접 건다.
     */
    @Test
    void 로그아웃_감사저장_실패는_204를_유지한다() throws Exception {
        AuditLogWriter writer = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new DataAccessResourceFailureException("audit down"))
                .when(writer).write(eq(adminId), any(), any(), any());
        mvc.perform(delete(LOGIN_PATH).cookie(accessToken(adminId, Role.SUPER_ADMIN)))
                .andExpect(status().isNoContent());
    }

    @Test
    void 깨진_JSON의_비밀번호가_예외_로그에_남지_않는다() throws Exception {
        var appender = attach(GlobalExceptionHandler.class);
        try {
            mvc.perform(login("{\"loginId\":\"" + loginId + "\",\"password\":\"SensitivePassword!\" BROKEN}"))
                    .andExpect(status().isBadRequest());
            assertThat(appender.list).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain("SensitivePassword!"));
        } finally {
            detach(GlobalExceptionHandler.class, appender);
        }
    }

    @Test
    void 로그인_성공과_실패를_본문이나_토큰없이_감사_로그로_남긴다() throws Exception {
        var appender = attach(AdminLoginAuditFilter.class);
        try {
            mvc.perform(login(body)).andExpect(status().isOk());
            mvc.perform(login(requestBody("login.absent", PASSWORD))).andExpect(status().isUnauthorized());
            assertThat(appender.list).hasSize(2).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("event=ADMIN_LOGIN", "occurredAt=",
                            "clientIp=" + clientIp).doesNotContain(PASSWORD, "password", "refreshToken"));
            assertThat(appender.list.get(0).getFormattedMessage()).contains("success=true", "status=200");
            assertThat(appender.list.get(1).getFormattedMessage()).contains("success=false", "status=401");
        } finally {
            detach(AdminLoginAuditFilter.class, appender);
        }
    }

    /*
     * DB 백업을 커밋한 뒤 최종 검사 전에 계정이 바뀌는 창을 만든다. 그 두 조회 사이에서 앱이
     * 부르는 것이 JWT 생성이라 그 자리에 계정 변경을 끼운다. 목으로 두 번째 조회의 응답을
     * 꾸미던 전과 달리 실제 행을 바꾼다.
     */
    @ParameterizedTest
    @EnumSource(AccountChange.class)
    void DB_백업후_계정이_사용불가이면_AUTH001이며_쿠키를_발급하지_않는다(AccountChange change) throws Exception {
        doAnswer(call -> {
            changeAccount(change);
            return call.callRealMethod();
        }).when(jwt).createAccessToken(eq(adminId), any(Role.class), anyMap());
        mvc.perform(login(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(tokens);
        verify(admins).clearRefreshTokenIfMatches(eq(adminId), any(), any());
    }

    enum AccountChange { MISSING, INACTIVE, ROLE_CHANGED }

    /*
     * DELETED 는 deleted_at 과 토큰 해시까지 함께 맞춰야 한다. admin 의 CHECK 제약이 그 셋을
     * 한 묶음으로 본다.
     */
    private void changeAccount(AccountChange change) {
        switch (change) {
            case MISSING -> jdbc.update("delete from admin where admin_id = ?", adminId);
            case INACTIVE -> jdbc.update(
                    "update admin set status = 'DELETED', deleted_at = ?, refresh_token_hash = null, "
                            + "refresh_token_expires_at = null where admin_id = ?", LocalDateTime.now(), adminId);
            case ROLE_CHANGED -> jdbc.update("update admin set role = 'ADMIN' where admin_id = ?", adminId);
        }
    }

    @ParameterizedTest
    @EnumSource(FinalCheckFailure.class)
    void 최종_DB_검사_장애는_AUTH002이며_쿠키를_발급하지_않는다(FinalCheckFailure failure) throws Exception {
        doAnswer(call -> {
            breakFinalCheck(failure);
            return call.callRealMethod();
        }).when(jwt).createAccessToken(eq(adminId), any(Role.class), anyMap());
        var appender = attach(GlobalExceptionHandler.class);
        try {
            mvc.perform(login(body))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
            verify(admins).clearRefreshTokenIfMatches(eq(adminId), any(), any());
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
            if (failure.tokensUntouched) {
                verifyNoInteractions(tokens);
            }
        } finally {
            detach(GlobalExceptionHandler.class, appender);
        }
    }

    /*
     * 보상 트랜잭션은 성공해야 한다. 한 번만 던지고 다음 호출부터 실제 매니저에 위임한다.
     * 전에는 목에 성공 응답을 여럿 줄 세워 같은 일을 했다.
     *
     * 커밋은 실제로 끝낸 다음 던진다. 커밋 자체를 가로채 버리면 트랜잭션이 스레드에 묶인 채
     * 남아서, 같은 스레드로 이어 도는 테스트가 전부 트랜잭션 시간 초과로 떨어진다. 앱이 보는
     * 것도 "커밋은 됐는데 응답이 실패로 왔다" 쪽이라 이 모양이 실제에 가깝다.
     */
    /*
     * 커밋이 실패하는 경우만 캐시 게시가 이미 지나 있다. 그 기대를 enum 값이 들고 있어서
     * 검증 단계에 장애 종류를 다시 묻는 분기를 두지 않는다(UT-3-04).
     */
    enum FinalCheckFailure {
        DB_READ(true), TRANSACTION_BEGIN(true), TRANSACTION_COMMIT(false);

        private final boolean tokensUntouched;

        FinalCheckFailure(boolean tokensUntouched) {
            this.tokensUntouched = tokensUntouched;
        }
    }

    private void breakFinalCheck(FinalCheckFailure failure) {
        switch (failure) {
            case DB_READ -> doThrow(new DataAccessResourceFailureException("final DB read failed"))
                    .when(admins).findByIdForUpdate(adminId);
            case TRANSACTION_BEGIN -> doThrow(new CannotCreateTransactionException("final transaction failed"))
                    .doCallRealMethod().when(transactions).getTransaction(any());
            case TRANSACTION_COMMIT -> doAnswer(call -> {
                call.callRealMethod();
                throw new TransactionSystemException("final commit failed");
            }).doCallRealMethod().when(transactions).commit(any());
        }
    }

    @Test
    void 재발급은_만료된_Access없이도_새_쿠키와_관리자정보를_반환한다() throws Exception {
        backUpRefreshToken(adminId);
        tokens.saveIfNewer(REFRESH_TOKEN, adminId, Role.SUPER_ADMIN, true, Duration.ofDays(1), 1L);
        var response = mvc.perform(refresh().cookie(
                        new Cookie("refreshToken", REFRESH_TOKEN), new Cookie("accessToken", "expired")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.adminId").value(adminId.intValue()))
                .andExpect(jsonPath("$.data.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn().getResponse();
        assertThat(response.getCookie("refreshToken").getPath()).isEqualTo("/v1/admin/auth/");
        assertThat(response.getCookie("refreshToken").getMaxAge()).isEqualTo(86400);
        assertThat(response.getCookie("accessToken").getMaxAge()).isEqualTo(1800);
    }

    @Test
    void 재발급_쿠키_누락은_AUTH003이며_새_쿠키가_없다() throws Exception {
        mvc.perform(refresh())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-003"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 재발급_DB장애는_AUTH002이며_새_쿠키를_발급하지_않는다() throws Exception {
        doThrow(new DataAccessResourceFailureException("DB failed"))
                .when(admins).findIdByRefreshTokenHash(any());
        mvc.perform(refresh().cookie(new Cookie("refreshToken", REFRESH_TOKEN)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 토큰_재사용은_AUTH004이며_새_쿠키를_발급하지_않는다() throws Exception {
        var owner = new RefreshTokenRepository.RefreshTokenData(adminId, Role.SUPER_ADMIN, true);
        doReturn(Optional.of(owner)).when(tokens).find(REFRESH_TOKEN);
        doReturn(RefreshTokenRepository.RotateOutcome.reuseDetected(owner))
                .when(tokens).compareAndRotate(eq(REFRESH_TOKEN), any(), any());
        mvc.perform(refresh().cookie(new Cookie("refreshToken", REFRESH_TOKEN)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-004"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }
}
