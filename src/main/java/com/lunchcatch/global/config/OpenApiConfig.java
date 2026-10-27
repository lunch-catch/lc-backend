package com.lunchcatch.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/*
 * 역할마다 다른 Access Token 쿠키를 스킴으로 선언한다. 스킴 이름은 쿠키 이름과 같다.
 * 이름은 auth.AuthCookieNames 의 상수와 같아야 하지만 기술 공통은 auth 를 의존하지 못해 값을 그대로 적는다.
 * 둘이 같은지는 OpenApiSecurityIntegrationTest 가 본다. 어느 작업에 어느 스킴을 거는지는 auth.AuthOpenApiConfig 가 경로로 정한다(#891).
 *
 * 재발급과 로그아웃이 쓰는 refreshToken 쿠키는 스킴으로 두지 않는다.
 * 그 쿠키는 역할별 Path 로 좁혀져 있어 Swagger UI 에서 넣을 방법이 없고, 자물쇠만 떠서 넣으면 부를 수 있는 것처럼 오해하게 한다.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "LunchCatch API",
                version = "v1",
                description = "LunchCatch REST API 문서입니다. 인증된 API는 HttpOnly 쿠키 기반 Access Token을 사용합니다."
        )
)
@SecurityScheme(
        name = "adminAccessToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "adminAccessToken",
        description = "관리자 로그인 성공 응답이 설정하는 HttpOnly Access Token 쿠키입니다. 관리자 또는 최고 관리자 권한이 필요합니다."
)
@SecurityScheme(
        name = "ownerAccessToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "ownerAccessToken",
        description = "점주 로그인 성공 응답이 설정하는 HttpOnly Access Token 쿠키입니다. 점주 권한이 필요합니다."
)
@SecurityScheme(
        name = "accessToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "accessToken",
        description = "회원 로그인 성공 응답이 설정하는 HttpOnly Access Token 쿠키입니다."
)
public class OpenApiConfig {
}
