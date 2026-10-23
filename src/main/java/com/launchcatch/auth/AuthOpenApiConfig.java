package com.launchcatch.auth;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * API 문서의 모든 작업에 경로로 고른 Access Token 쿠키 스킴을 건다(#891). 컨트롤러마다 붙이지 않는다.
 * 붙이는 것을 기억해야 하면 새 엔드포인트에서 빠지고, 빠지면 인증 API 가 공개처럼 보인다.
 * 여기서는 빠뜨려도 공개 API 가 인증으로 보이는 쪽으로 틀린다. 주장되지 않은 경로를 막는 defaultFilterChain 과 같은 방향이다.
 *
 * 역할은 AuthCookieNames.accessTokenCookieNameForPath 로 고른다. 요청에서 쿠키를 고르는 규칙과 같아 규칙이 한 곳에 있다.
 * 공개 작업은 도메인 체인의 permitAll 과 같아야 하고, 둘이 같은지는 OpenApiSecurityIntegrationTest 가 본다(#894).
 * 로그인(POST)과 로그아웃(DELETE)이 같은 경로라 메서드까지 함께 적는다.
 * 기술 공통(global)은 auth 를 의존하지 못해 이 설정을 auth 에 둔다. SecurityConfig 가 여기 있는 이유와 같다.
 */
@Configuration
public class AuthOpenApiConfig {

    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "POST /v1/auth/tokens", "POST /v1/auth/tokens:refresh", "GET /v1/auth/kakao/authorize",
            "POST /v1/owner/auth/tokens", "POST /v1/owner/auth/tokens:refresh", "POST /v1/owners",
            "POST /v1/admin/auth/tokens", "POST /v1/admin/auth/tokens:refresh");

    // 외부 서비스가 부르는 웹훅이다. 도메인 체인이 메서드와 관계없이 연다
    private static final String WEBHOOK_PATH = "/webhook/";

    @Bean
    OpenApiCustomizer cookieSecurityRequirement() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (path.startsWith(WEBHOOK_PATH)) {
                return;
            }
            SecurityRequirement requirement = new SecurityRequirement()
                    .addList(AuthCookieNames.accessTokenCookieNameForPath(path));
            item.readOperationsMap().forEach((method, operation) -> {
                if (!PUBLIC_OPERATIONS.contains(method.name() + " " + path)) {
                    operation.setSecurity(List.of(requirement));
                }
            });
        });
    }
}
