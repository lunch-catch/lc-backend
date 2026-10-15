package com.launchcatch.owner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.owner.repository.OwnerRepository;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * GET /v1/owner/me 의 보안 체인을 실제 입구부터 확인한다.
 *
 * 전에는 @SpringBootTest(classes = ...) 로 보안 설정과 컨트롤러만 담고 저장소와 Valkey 를 목으로
 * 바꿨다. 그 모양은 슬라이스라 통합 테스트가 아니고(LLM 구현 플로우 5단계), 컨텍스트를 하나 더
 * 띄워 테스트 JVM 의 힙을 깎았다(#278).
 *
 * 전체를 띄우면 JWT 검증이 계정 존재와 현재 역할까지 확인한다. 목을 쓰던 때는 그 경로를 타지
 * 않아 관리자 토큰의 403 을 느슨하게 보고 있었다. 그래서 역할 테스트가 실제 계정을 준비한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerProfileSecurityIntegrationTest extends IntegrationTestSupport {

    private static final String PATH = "/v1/owner/me";
    private static final long NO_SUCH_OWNER_ID = 9_000_001L;
    // OwnerAccessTokenPolicy 의 상수가 private 이라 같은 값을 여기에 둔다.
    private static final String VERSION_CLAIM = "issuanceVersion";

    @Autowired MockMvc mvc;
    @Autowired OwnerRepository owners;
    @Autowired OwnerAccessTokenVersionRepository versions;
    @Autowired JwtTokenProvider jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-validity-ms}")
    private long accessTokenValidityMs;

    private Owner owner(OwnerStatus status) {
        Owner owner = owners.saveAndFlush(
                Owner.create(UUID.randomUUID() + "@example.com", passwords.encode("password12")));
        jdbc.update("update owner set status = ?, tutorial_viewed = ? where owner_id = ?",
                status.name(), status == OwnerStatus.ACTIVE, owner.getId());
        return owner;
    }

    /*
     * 점주 토큰은 운영과 같이 발급 순번 클레임을 함께 넣는다. 그 클레임을 로그인과 재발급
     * 서비스만 넣고, 없으면 OwnerAccessTokenPolicy 가 0 으로 보아 폐기 판정이 달라진다.
     */
    private Cookie token(Long id, Role role) {
        String value = new TransactionTemplate(manager).execute(tx -> role == Role.OWNER
                ? jwt.createAccessToken(id, role, Map.of(VERSION_CLAIM, Long.toString(issuanceVersion(id))))
                : jwt.createAccessToken(id, role));
        return new Cookie("accessToken", value);
    }

    // 계정이 없는 식별자로도 토큰을 만들어야 해서 행이 없으면 0 으로 본다.
    private long issuanceVersion(Long ownerId) {
        return jdbc.queryForList("select refresh_token_issuance_version from owner where owner_id = ?",
                        Long.class, ownerId)
                .stream().findFirst().orElse(0L);
    }

    /*
     * 관리자 JWT 검증이 계정 존재와 현재 역할을 확인하므로 유효한 계정을 준비한다.
     * 사용자 역할은 그 확인을 타지 않아 임의의 식별자로 충분하다.
     */
    private Cookie nonOwnerToken(Role role) {
        if (role == Role.MEMBER) {
            return token(1L, role);
        }
        String loginId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                loginId, passwords.encode("password12"), "owner-profile-test-admin", role.name(), now, now);
        Long adminId = jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
        return token(adminId, role);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ONBOARDING", "ACTIVE"})
    void 쿠키로_인증한_본인의_정보만_조회한다(OwnerStatus ownerStatus) throws Exception {
        Owner owner = owner(ownerStatus);

        mvc.perform(get(PATH).param("ownerId", "99").cookie(token(owner.getId(), Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.email").value(owner.getEmail()))
                .andExpect(jsonPath("$.data.status").value(ownerStatus.name()))
                .andExpect(jsonPath("$.data.tutorialViewed").value(ownerStatus == OwnerStatus.ACTIVE))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.refreshTokenHash").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void 쿠키가_없으면_401이다() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 잘못된_쿠키는_401이다() throws Exception {
        mvc.perform(get(PATH).cookie(new Cookie("accessToken", "invalid-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    /*
     * 서명은 맞고 만료만 지난 토큰이다. 운영과 같은 비밀키로 서명해야 만료가 아닌 서명 불일치로
     * 떨어지는 것을 가린다.
     */
    @Test
    void 만료된_실제_JWT는_401이다() throws Exception {
        Owner owner = owner(OwnerStatus.ACTIVE);
        JwtTokenProvider expired = new JwtTokenProvider(secret, accessTokenValidityMs,
                accessTokenValidityMs, accessTokenValidityMs, accessTokenValidityMs,
                Clock.offset(Clock.systemUTC(), Duration.ofMillis(-accessTokenValidityMs * 2)));

        mvc.perform(get(PATH).cookie(new Cookie("accessToken",
                        expired.createAccessToken(owner.getId(), Role.OWNER))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    /*
     * 점주 토큰의 무효화는 발급 순번으로 한다. 전에 이 자리가 AccessTokenValidAfterRepository 를
     * 목으로 바꿔 거짓을 돌려주고 401 을 봤는데, 실제 앱은 점주 역할에 그 저장소를 쓰지 않는다
     * (OwnerAccessTokenPolicy.supports). 즉 쓰이지 않는 경로를 검증하고 있었다.
     */
    @Test
    void 무효화된_토큰은_401이다() throws Exception {
        Owner owner = owner(OwnerStatus.ACTIVE);
        Cookie issued = token(owner.getId(), Role.OWNER);
        versions.invalidateThroughVersion(owner.getId(), issuanceVersion(owner.getId()));

        mvc.perform(get(PATH).cookie(issued))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주가_아닌_토큰은_403이다(Role role) throws Exception {
        mvc.perform(get(PATH).cookie(nonOwnerToken(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 계정이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH).cookie(token(NO_SUCH_OWNER_ID, Role.OWNER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 조회_경로의_다른_메서드는_허용하지_않는다() throws Exception {
        Owner owner = owner(OwnerStatus.ACTIVE);

        mvc.perform(post(PATH).cookie(token(owner.getId(), Role.OWNER)))
                .andExpect(status().isForbidden());
    }
}
