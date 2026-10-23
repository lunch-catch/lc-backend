package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.entity.Owner;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/*
 * DELETE /v1/owner/auth/tokens 의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 로 보안 설정과 컨트롤러만 담은 슬라이스였다. 그 모양은
 * 통합 테스트가 아니고(구현 플로우 5단계) 컨텍스트를 하나 더 띄웠다(#278).
 *
 * 폐기 저장소와 로그아웃 서비스는 기반 클래스가 스파이로 준다. 목이 아니라 스파이라서 기본은
 * 실제 구현이고, 장애를 만들어야 하는 테스트만 그 호출을 스텁한다. 스파이에는 when(...) 이
 * 실제 메서드를 먼저 부르므로 doReturn 과 doThrow 로 스텁한다.
 *
 * 계정은 실제 행으로 둔다. 로그아웃이 목이던 때는 계정이 없어도 204 가 나왔는데, 전체를 띄우면
 * 그 행을 잠그고 토큰을 지운다. 관리자 토큰의 403 도 같은 이유로 관리자 행을 만든다. 관리자
 * 정책이 그 행의 폐기 기준을 읽어서, 계정이 없으면 인증에서 먼저 떨어진다.
 */
class OwnerLogoutSecurityIntegrationTest extends AbstractOwnerIntegrationTest {

    private static final String PATH = "/v1/owner/auth/tokens";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        reset(logoutService, revocations, versions);
        ownerId = owners.saveAndFlush(
                Owner.create(UUID.randomUUID() + "@example.com", passwords.encode("password12"))).getId();
    }

    private Cookie ownerToken() {
        return new Cookie("ownerAccessToken", jwt.createAccessToken(ownerId, Role.OWNER));
    }

    /*
     * 사용자 역할은 담당 정책이 없어 발급 시각 기준으로만 검증하니 임의의 식별자로 충분하다.
     * 관리자 역할은 그 정책이 계정 행을 읽으므로 유효한 계정을 만든다.
     */
    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return new Cookie("ownerAccessToken", jwt.createAccessToken(1L, role));
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, passwords.encode("password12"), "owner-logout-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        return new Cookie("ownerAccessToken", jwt.createAccessToken(adminId, role));
    }

    @Test
    void RT_쿠키없이_로그아웃하고_발급과_같은_Path로_쿠키를_삭제한다() throws Exception {
        var response = mvc.perform(delete(PATH)
                        .cookie(ownerToken()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andReturn().getResponse();

        verify(logoutService).logout(ownerId);
        assertThat(response.getHeaders("Set-Cookie")).hasSize(2)
                .anySatisfy(c -> assertThat(c).contains("ownerAccessToken=", "Path=/;", "Max-Age=0", "HttpOnly",
                        "Secure", "SameSite=Strict"))
                .anySatisfy(c -> assertThat(c).contains("refreshToken=", "Path=/v1/owner/auth/;", "Max-Age=0",
                        "HttpOnly", "Secure", "SameSite=Strict"));
    }

    @Test
    void AT가_없거나_잘못됐으면_401이다() throws Exception {
        mvc.perform(delete(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
        mvc.perform(delete(PATH).cookie(new Cookie("ownerAccessToken", "invalid")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(logoutService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN", "MEMBER"})
    void 다른_역할은_403이다(Role role) throws Exception {
        mvc.perform(delete(PATH).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));

        verifyNoInteractions(logoutService);
    }

    @Test
    void 차단된_AT는_401이다() throws Exception {
        doReturn(Optional.of(0L)).when(revocations).findRevokedVersion(ownerId);

        mvc.perform(delete(PATH).cookie(ownerToken()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(logoutService);
    }

    @Test
    void Redis_장애여도_DB에서_폐기한_AT는_401이다() throws Exception {
        doReturn(Optional.of(1L)).when(revocations).findRevokedVersion(ownerId);
        doThrow(new DataAccessResourceFailureException("Redis")).when(versions).isValid(any(), anyLong());

        mvc.perform(delete(PATH).cookie(ownerToken()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(logoutService);
    }

    @Test
    void DB_조회_장애시_인증을_거부한다() throws Exception {
        doThrow(new DataAccessResourceFailureException("DB")).when(revocations).findRevokedVersion(ownerId);

        mvc.perform(delete(PATH).cookie(ownerToken()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(logoutService);
    }

    @Test
    void 폐기_실패시_503이며_성공_쿠키를_내리지_않는다() throws Exception {
        doThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE))
                .when(logoutService).logout(ownerId);

        mvc.perform(delete(PATH).cookie(ownerToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
