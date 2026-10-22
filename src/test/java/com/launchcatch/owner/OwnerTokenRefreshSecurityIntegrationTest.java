package com.launchcatch.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.service.OwnerTokenRefreshResult;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/*
 * POST /v1/owner/auth/tokens:refresh 의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 로 보안 설정과 컨트롤러만 담은 슬라이스였다. 그 모양은
 * 통합 테스트가 아니고(구현 플로우 5단계) 컨텍스트를 하나 더 띄웠다(#278).
 *
 * 재발급 서비스는 기반 클래스가 스파이로 준다. 이 테스트가 보는 것은 체인이 AT 없이 RT 만으로
 * 통과시키는지이므로 서비스의 응답은 스텁으로 고정한다. 스파이에는 when(...) 이 실제 메서드를
 * 먼저 부르므로 doReturn 과 doThrow 로 스텁한다.
 */
class OwnerTokenRefreshSecurityIntegrationTest extends AbstractOwnerIntegrationTest {

    private static final String PATH = "/v1/owner/auth/tokens:refresh";

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired PasswordEncoder passwords;

    @Value("${jwt.secret}")
    private String secret;

    private Long ownerId;

    /*
     * 계정을 실제 행으로 둔다. AT 를 들고 오는 테스트가 있고, 점주 정책이 그 행의 폐기 기준을
     * 읽어서 계정이 없으면 인증에서 먼저 떨어진다. 목만 쓰던 때는 그 경로를 타지 않았다.
     */
    @BeforeEach
    void setUp() {
        reset(refreshService);
        ownerId = owners.saveAndFlush(
                Owner.create(UUID.randomUUID() + "@example.com", passwords.encode("password12"))).getId();
        doReturn(new OwnerTokenRefreshResult(
                new OwnerLoginResponse("owner@example.com", Role.OWNER, OwnerStatus.ACTIVE, false),
                jwt.createAccessToken(ownerId, Role.OWNER), "new-refresh"))
                .when(refreshService).refresh("valid-refresh");
    }

    @Test
    void 보안체인은_AT없이_RT만으로_재발급을_허용한다() throws Exception {
        var response = mvc.perform(post(PATH).cookie(new Cookie("refreshToken", "valid-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn().getResponse();

        assertThat(response.getCookie("accessToken").getSecure()).isTrue();
        assertThat(response.getCookie("refreshToken").getSecure()).isTrue();
        assertThat(jwt.getRole(response.getCookie("accessToken").getValue())).isEqualTo(Role.OWNER);
    }

    /*
     * 서명은 맞고 만료만 지난 AT 다. 운영과 같은 비밀키로 서명해야 만료가 아닌 서명 불일치로
     * 떨어지는 것을 가린다.
     */
    @Test
    void 만료된_AT가_동봉되어도_재발급할_수_있다() throws Exception {
        var expiredJwt = new JwtTokenProvider(secret, 1800000, 86400000, 86400000, 1209600000,
                Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneId.of("Asia/Seoul")));

        mvc.perform(post(PATH).cookie(
                        new Cookie("refreshToken", "valid-refresh"),
                        new Cookie("accessToken", expiredJwt.createAccessToken(ownerId, Role.OWNER))))
                .andExpect(status().isOk());
    }

    @Test
    void 쿠키가_없으면_보안체인에서_AT를_요구하는_대신_AUTH003을_반환한다() throws Exception {
        doThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID))
                .when(refreshService).refresh(null);

        mvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
    }

    @Test
    void 재발급_경로는_POST외의_메서드를_허용하지_않는다() throws Exception {
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", jwt.createAccessToken(ownerId, Role.OWNER))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(refreshService);
    }
}
