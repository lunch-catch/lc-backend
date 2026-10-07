package com.launchcatch.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "LunchCatch API",
                version = "v1",
                description = "LunchCatch REST API 문서입니다. 인증된 API는 HttpOnly 쿠키 기반 Access Token을 사용합니다."
        )
)
@SecurityScheme(
        name = "memberAccessToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "accessToken",
        description = "회원 로그인 성공 응답이 설정하는 HttpOnly Access Token 쿠키입니다."
)
public class OpenApiConfig {
}
