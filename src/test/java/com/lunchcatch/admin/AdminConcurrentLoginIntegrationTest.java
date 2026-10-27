package com.lunchcatch.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.opaque.TokenHasher;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class AdminConcurrentLoginIntegrationTest extends AbstractAdminIntegrationTest {
    private static final String PATH = "/v1/admin/auth/tokens";
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    private Long adminId;
    private String body;

    @BeforeEach
    void setUp() {
        String loginId = "session." + UUID.randomUUID().toString().substring(0, 8);
        adminId = admins.saveAndFlush(Admin.register(loginId, encoder.encode("Freshman!2026"), "심사위원", Role.SUPER_ADMIN)).getId();
        body = "{\"loginId\":\"" + loginId + "\",\"password\":\"Freshman!2026\"}";
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from admin_login_session where admin_id = ?", adminId);
        jdbc.update("delete from audit_log where admin_id = ?", adminId);
        admins.deleteById(adminId);
    }

    @Test
    void 세션이_있는_관리자의_직접_삭제는_DB가_거부한다() throws Exception {
        var browser = login();

        assertThatThrownBy(() -> jdbc.update("delete from admin where admin_id = ?", adminId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertAuthorized(browser);
    }

    @Test
    void 두_기기의_로그인과_한쪽의_갱신이_다른쪽_토큰을_폐기하지_않는다() throws Exception {
        var first = login();
        var second = login();
        assertThat(first.refresh().getValue()).isNotEqualTo(second.refresh().getValue());
        assertAuthorized(first);
        assertAuthorized(second);
        var renewed = refresh(first);
        assertAuthorized(renewed);
        assertAuthorized(second);
        assertAuthorized(refresh(second));
        assertThat(jdbc.queryForObject("select count(*) from admin_login_session where admin_id = ? and revoked = false", Long.class, adminId)).isEqualTo(2);
    }

    @Test
    void 다른_탭이_공유한_동일_쿠키로_사용하고_재로그인한_쿠키도_공유한다() throws Exception {
        var shared = login();
        assertAuthorized(shared);
        assertAuthorized(shared);
        var replacedCookie = login();
        assertAuthorized(replacedCookie);
        assertAuthorized(replacedCookie);
        assertAuthorized(refresh(shared));
    }

    @Test
    void 한_기기의_로그아웃은_그_세션만_폐기한다() throws Exception {
        var first = login();
        var second = login();
        mvc.perform(delete(PATH).cookie(first.access())).andExpect(status().isNoContent());
        mvc.perform(get("/v1/admin/admins").cookie(first.access())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isUnauthorized());
        assertAuthorized(second);
        assertAuthorized(refresh(second));
        assertThat(admins.findById(adminId).orElseThrow().getAccessTokenRevokedVersion()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_login_session where admin_id = ? and revoked = true", Long.class, adminId)).isEqualTo(1);
    }

    @Test
    void 재사용한_RT는_해당_세션_RT만_폐기하며_다른_세션은_유지된다() throws Exception {
        var first = login();
        var second = login();
        var renewed = refresh(first);
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-004"));
        assertAuthorized(renewed);
        mvc.perform(client(post(PATH + ":refresh")).cookie(renewed.refresh())).andExpect(status().isUnauthorized());
        assertAuthorized(second);
        assertAuthorized(refresh(second));
    }

    @Test
    void 서로_다른_기기의_로그인_요청이_동시에_와도_둘_다_유지된다() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> concurrentLogin(ready, start));
            var second = workers.submit(() -> concurrentLogin(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertAuthorized(a);
            assertAuthorized(b);
            assertAuthorized(refresh(a));
            assertAuthorized(refresh(b));
        } finally { start.countDown(); }
    }

    @Test
    void 서로_다른_세션의_동시_갱신도_각각_성공한다() throws Exception {
        var first = login();
        var second = login();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> refresh(first));
            var b = workers.submit(() -> refresh(second));
            assertAuthorized(a.get(20, TimeUnit.SECONDS));
            assertAuthorized(b.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void 계정_비활성화는_모든_세션의_AT와_RT를_차단한다() throws Exception {
        var first = login();
        var second = login();
        jdbc.update("update admin set status = 'DELETED', deleted_at = ? where admin_id = ?", LocalDateTime.now(), adminId);
        mvc.perform(get("/v1/admin/admins").cookie(first.access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/admin/admins").cookie(second.access())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(second.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void 계정_전체_폐기_기준은_세션_AT와_RT에도_적용된다() throws Exception {
        var first = login();
        var second = login();
        jdbc.update("update admin set access_token_revoked_version = refresh_token_issuance_version where admin_id = ?", adminId);
        mvc.perform(get("/v1/admin/admins").cookie(first.access())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(second.refresh())).andExpect(status().isUnauthorized());
        assertAuthorized(login());
    }

    @Test
    void Redis_조회_장애에서_DB_복구로_갱신하고_다른_세션은_유지한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable")).when(sessionTokens).find(any());
        doThrow(new DataAccessResourceFailureException("unavailable")).when(versions).isVersionValid(any(), any(), anyLong());
        var first = refresh(login());
        var second = login();
        mvc.perform(delete(PATH).cookie(first.access())).andExpect(status().isNoContent());
        assertAuthorized(second);
    }

    @Test
    void 만료된_세션은_AT가_남아도_인증과_갱신을_거부한다() throws Exception {
        var first = login();
        jdbc.update("update admin_login_session set expires_at = ? where admin_id = ?", LocalDateTime.now().minusSeconds(1), adminId);
        mvc.perform(get("/v1/admin/admins").cookie(first.access())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void 세션_캐시가_유실되어도_DB_현재_해시로_복구한다() throws Exception {
        var first = login();
        var sessionId = sessionId(first);
        redis.delete("adminSession:refreshToken:" + TokenHasher.sha256(first.refresh().getValue()));
        assertAuthorized(refresh(first));
        assertThat(jdbc.queryForObject("select refresh_token_version from admin_login_session where id = ?", Long.class, sessionId)).isEqualTo(2);
    }

    @Test
    void 로그아웃_Redis_실패도_DB_폐기를_취소하지_않고_다른_기기는_유지한다() throws Exception {
        var first = login();
        var second = login();
        Long firstSessionId = sessionId(first);
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(sessionTokens).revokeBeforeVersion(eq(firstSessionId), any(), anyLong());
        mvc.perform(delete(PATH).cookie(first.access())).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/v1/admin/admins").cookie(first.access())).andExpect(status().isUnauthorized());
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isUnauthorized());
        assertAuthorized(refresh(second));
    }

    @Test
    void 로그아웃_후_오래된_게시가_와도_세션을_되살리지_않는다() throws Exception {
        var first = login();
        var id = sessionId(first);
        mvc.perform(delete(PATH).cookie(first.access())).andExpect(status().isNoContent());
        assertThat(sessionTokens.saveIfNewer(first.refresh().getValue(), id, Role.SUPER_ADMIN,
                java.time.Duration.ofDays(1), 1L)).isFalse();
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isUnauthorized());
    }

    @Test
    void DB_회전_실패가_롤백되면_Redis도_이전_토큰으로_복구한다() throws Exception {
        var first = login();
        com.lunchcatch.ops.contract.AuditLogWriter auditTarget =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditTarget).write(eq(adminId), eq("ADMIN_TOKEN_REISSUE"), any(), any());
        mvc.perform(client(post(PATH + ":refresh")).cookie(first.refresh())).andExpect(status().isServiceUnavailable());
        reset(auditTarget);
        assertAuthorized(refresh(first));
    }

    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;

    private Long sessionId(Browser browser) {
        long version = AdminAccessTokenClaims.issuanceVersion(jwt.getAdditionalClaims(browser.access().getValue()));
        return jdbc.queryForObject("select id from admin_login_session where admin_id = ? and issuance_version = ?", Long.class, adminId, version);
    }

    private Browser concurrentLogin(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("시작 신호 없음"); }
        return login();
    }

    private Browser login() throws Exception {
        return browser(mvc.perform(client(post(PATH)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private Browser refresh(Browser browser) throws Exception {
        return browser(mvc.perform(client(post(PATH + ":refresh")).cookie(browser.refresh()))
                .andExpect(status().isOk()).andReturn());
    }

    private Browser browser(MvcResult response) {
        return new Browser(response.getResponse().getCookie("adminAccessToken"), response.getResponse().getCookie("refreshToken"));
    }

    private void assertAuthorized(Browser browser) throws Exception {
        mvc.perform(get("/v1/admin/admins").cookie(browser.access())).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder client(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        int client = CLIENTS.incrementAndGet();
        return request.with(r -> { r.setRemoteAddr("198.18." + (client / 250) + "." + (client % 250 + 1)); return r; });
    }

    private record Browser(Cookie access, Cookie refresh) { }
}
