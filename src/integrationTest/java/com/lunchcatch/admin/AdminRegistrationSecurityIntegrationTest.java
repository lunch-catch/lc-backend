package com.lunchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/*
 * POST /v1/admin/admins 의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 로 보안 설정과 서비스만 담고 저장소와 암호 인코더와
 * 트랜잭션 매니저까지 목으로 바꾼 슬라이스였다. 그 모양은 통합 테스트가 아니고(구현 플로우 5단계)
 * 컨텍스트를 하나 더 띄웠다(#278).
 *
 * 발급 성공은 실제 행과 실제 트랜잭션으로 본다. 그래서 트랜잭션 매니저를 목으로 둘 필요가 없다.
 * 인증을 통과시키는 토큰과 폐기 판정만 기반 클래스의 스파이로 스텁한다. 스파이에는 when(...) 이
 * 실제 메서드를 먼저 부르므로 doReturn 과 doThrow 로 스텁한다.
 */
class AdminRegistrationSecurityIntegrationTest extends AbstractAdminIntegrationTest {

    private static final String PATH = "/v1/admin/admins";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 8, 10, 0);

    @Autowired MockMvc mvc;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired FilterChainProxy proxy;

    @Autowired
    @Qualifier("adminRegistrationSecurityFilterChain")
    SecurityFilterChain registrationChain;

    private Long issuerId;
    private String body;

    /*
     * 발급자를 실제 행으로 둔다. 쿠키의 "token" 은 서명하지 않은 문자열이고, 인증을 통과시키는
     * 판정만 스파이로 고정한다. 보려는 것이 체인의 선택과 거절이라 서명까지 실제로 만들 필요가 없다.
     */
    @BeforeEach
    void setUp() {
        reset(jwt, versions, admins, encoder, audit);
        issuerId = insertAdmin(Role.SUPER_ADMIN);
        body = """
                {"loginId":"%s","initialPassword":"Initial123!","name":"관리자","role":"ADMIN"}
                """.formatted("a" + UUID.randomUUID().toString().substring(0, 8));

        doReturn(true).when(jwt).validateToken("token");
        doReturn(issuerId).when(jwt).getId("token");
        doReturn(Role.SUPER_ADMIN).when(jwt).getRole("token");
        doReturn(ISSUED_AT).when(jwt).getIssuedAt("token");
        doReturn(AdminAccessTokenClaims.forVersion(1L)).when(jwt).getAdditionalClaims("token");
        doReturn(true).when(versions).isVersionValid(Role.SUPER_ADMIN, issuerId, 1L);
    }

    private Long insertAdmin(Role role) {
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, "$2a$10$notusedhashnotusedhashnotusedhashnotusedhashnotusedha",
                "registration-test-admin", role.name(), now, now);
        return jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
    }

    @Test
    void 넓은_관리자_체인보다_발급_체인이_먼저_선택된다() {
        var request = new MockHttpServletRequest(context.getServletContext(), "POST", PATH);
        request.setServletPath(PATH);
        assertThat(proxy.getFilterChains().stream().filter(chain -> chain.matches(request)).findFirst())
                .contains(registrationChain);

        var other = new MockHttpServletRequest(context.getServletContext(), "POST", "/v1/admin/auth/tokens");
        other.setServletPath("/v1/admin/auth/tokens");
        assertThat(registrationChain.matches(other)).isFalse();
    }

    @Test
    void Redis_장애는_503_AUTH002와_RetryAfter로_응답하고_저장하지_않는다() throws Exception {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(versions).isVersionValid(Role.SUPER_ADMIN, issuerId, 1L);

        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(post(PATH).cookie(new Cookie("adminAccessToken", "token"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AUTH-002"))
                    .andExpect(header().string("Retry-After", "1"));

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("AUTH-002");
            });
            verifyNoInteractions(encoder, audit);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 일반_관리자는_넓은_체인이_허용하더라도_발급에서_403이다() throws Exception {
        Long plainAdminId = insertAdmin(Role.ADMIN);
        doReturn(plainAdminId).when(jwt).getId("token");
        doReturn(Role.ADMIN).when(jwt).getRole("token");
        doReturn(true).when(versions).isVersionValid(Role.ADMIN, plainAdminId, 1L);

        mvc.perform(post(PATH).cookie(new Cookie("adminAccessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));

        verifyNoInteractions(encoder, audit);
    }

    @Test
    void 발급_시각_누락은_401이다() throws Exception {
        doReturn(null).when(jwt).getIssuedAt("token");

        mvc.perform(post(PATH).cookie(new Cookie("adminAccessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));

        verifyNoInteractions(encoder, audit);
    }

    @Test
    void 정상_폐기_확인과_현재_권한_확인_후_201로_발급한다() throws Exception {
        String loginId = readLoginIdFromBody();

        mvc.perform(post(PATH).cookie(new Cookie("adminAccessToken", "token"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(audit).write(issuerId, "ADMIN_ACCOUNT_CREATE", loginId, "role=ADMIN");
        assertThat(jdbc.queryForObject("select count(*) from admin where login_id = ?", Integer.class, loginId))
                .isOne();
    }

    private String readLoginIdFromBody() {
        return body.split("\"loginId\":\"")[1].split("\"")[0];
    }
}
