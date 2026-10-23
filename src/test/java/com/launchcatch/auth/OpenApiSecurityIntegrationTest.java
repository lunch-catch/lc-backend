package com.launchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
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

    @Autowired MockMvc mockMvc;

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
}
