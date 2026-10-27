package com.lunchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/*
 * 애플리케이션 전체를 띄워 /v3/api-docs 의 모든 작업이 경로에 맞는 쿠키 인증을 선언하는지 본다(#891).
 * 공개 작업은 도메인 체인의 permitAll 과 같다. 로그인(POST)과 로그아웃(DELETE)이 같은 경로라 메서드로 가른다.
 */
class OpenApiSecurityIntegrationTest extends AbstractAuthIntegrationTest {

    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "POST /v1/auth/tokens", "POST /v1/auth/tokens:refresh", "GET /v1/auth/kakao/authorize",
            "POST /v1/owner/auth/tokens", "POST /v1/owner/auth/tokens:refresh", "POST /v1/owners",
            "POST /v1/admin/auth/tokens", "POST /v1/admin/auth/tokens:refresh",
            "GET /webhook/kakao/unlink", "POST /webhook/kakao/unlink", "POST /webhook/payments");

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    private static final AnonymousAuthenticationToken ANONYMOUS = new AnonymousAuthenticationToken(
            "api-docs", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    @Autowired MockMvc mockMvc;
    @Autowired FilterChainProxy proxy;
    @Autowired WebApplicationContext context;

    private JsonNode apiDocs() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    @Test
    void 인증이_필요한_작업은_모두_경로의_역할에_맞는_쿠키_스킴을_가지고_공개_작업은_없다() throws Exception {
        JsonNode paths = apiDocs().get("paths");
        List<String> wrong = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(entry -> {
            if (!METHODS.contains(entry.getKey())) {
                return;
            }
            String operation = entry.getKey().toUpperCase() + " " + path.getKey();
            JsonNode security = entry.getValue().get("security");
            if (PUBLIC_OPERATIONS.contains(operation)) {
                counts.merge("public", 1, Integer::sum);
                if (security != null && !security.isEmpty()) {
                    wrong.add(operation + " 공개인데 security=" + security);
                }
                return;
            }
            counts.merge("auth", 1, Integer::sum);
            String expected = AuthCookieNames.accessTokenCookieNameForPath(path.getKey());
            if (security == null || security.size() != 1 || !security.get(0).has(expected)) {
                wrong.add(operation + " 기대=" + expected + " security=" + security);
            }
        }));

        assertThat(wrong).isEmpty();
        assertThat(counts.get("public")).isEqualTo(PUBLIC_OPERATIONS.size());
    }

    @Test
    void 쿠키_스킴은_역할별_Access_Token_쿠키_셋과_이름이_하나씩_맞는다() throws Exception {
        JsonNode schemes = apiDocs().get("components").get("securitySchemes");
        Map<String, String> declared = new HashMap<>();
        schemes.properties().forEach(scheme -> {
            assertThat(scheme.getValue().get("in").asText()).isEqualTo("cookie");
            declared.put(scheme.getKey(), scheme.getValue().get("name").asText());
        });

        Set<String> cookies = Set.of(AuthCookieNames.ADMIN_ACCESS_TOKEN_COOKIE_NAME,
                AuthCookieNames.OWNER_ACCESS_TOKEN_COOKIE_NAME, AuthCookieNames.MEMBER_ACCESS_TOKEN_COOKIE_NAME);
        assertThat(declared.keySet()).isEqualTo(cookies);
        declared.forEach((name, cookie) -> assertThat(cookie).isEqualTo(name));
    }

    /*
     * 문서가 공개로 적은 작업과 보안 체인이 익명에게 여는 작업이 같은지 본다(#894).
     * 작업마다 처음 맞는 체인을 고르고 그 체인의 인가 규칙에 익명 인증을 넣어 판정한다. 컨트롤러는 부르지 않는다.
     */
    @Test
    void 문서의_공개_작업과_보안_체인이_익명에게_여는_작업이_같다() throws Exception {
        Set<String> documented = new TreeSet<>();
        Set<String> opened = new TreeSet<>();
        apiDocs().get("paths").properties().forEach(path -> path.getValue().properties().forEach(entry -> {
            if (!METHODS.contains(entry.getKey())) {
                return;
            }
            String method = entry.getKey().toUpperCase();
            String operation = method + " " + path.getKey();
            JsonNode security = entry.getValue().get("security");
            if (security == null || security.isEmpty()) {
                documented.add(operation);
            }
            if (anonymousAllowed(method, path.getKey())) {
                opened.add(operation);
            }
        }));

        assertThat(documented).isEqualTo(opened);
    }

    // 경로 변수는 아무 값으로 채운다. 체인은 경로 모양만 보고 값은 보지 않는다
    private boolean anonymousAllowed(String method, String path) {
        String uri = path.replaceAll("\\{[^}]+}", "1");
        MockHttpServletRequest request = new MockHttpServletRequest(context.getServletContext(), method, uri);
        request.setServletPath(uri);
        SecurityFilterChain chain = proxy.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst().orElseThrow();
        AuthorizationFilter filter = chain.getFilters().stream()
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst().orElseThrow();
        AuthorizationResult result = filter.getAuthorizationManager().authorize(() -> ANONYMOUS, request);
        return result != null && result.isGranted();
    }
}
