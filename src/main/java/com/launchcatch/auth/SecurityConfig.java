package com.launchcatch.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/*
 * 도메인에 속하지 않는 체인만 여기 둔다.
 *
 * auth 패키지에 두는 이유는 의존 방향이다. 설계 문서 1.1절이 auth -> global 한 방향으로 정했고
 * global 은 아무것도 의존하지 않는다. 이 설정이 ApiSecurityDefaults 를 쓰므로 global 에 두면
 * global -> auth 가 생겨 방향이 뒤집히고 auth 와 global 사이에 순환이 닫힌다.
 * (auth 는 ClockConfig 를 읽어 이미 global 을 의존한다.)
 * 아키텍처 테스트가 이것을 잡았다.
 *
 * 도메인 경로의 인가는 각 도메인이 자기 패키지의 ~SecurityConfig 에서 소유한다.
 * 공통 배선(csrf, cors, 세션, 인증 필터, 오류 위임)은 ApiSecurityDefaults 가 한 곳에 갖는다.
 * 도메인 경로를 이 파일에 적지 않는 것이 요점이다. 적기 시작하면 모든 작업의 충돌 지점이 된다.
 *
 * 체인 순서
 *   1                    액추에이터. 8081 자식 컨텍스트
 *   10                   플랫폼 공개 경로(springdoc)
 *   100                  도메인 체인들. 경로가 겹치지 않아 서로 같은 값이다
 *   LOWEST_PRECEDENCE    나머지 전부. 어느 도메인도 주장하지 않은 경로를 막는다
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int PLATFORM_CHAIN_ORDER = 10;

    /** 도메인이 없는 플랫폼 경로다. springdoc 이 만드는 것뿐이라 여기 직접 둔다. */
    private static final String[] PLATFORM_PUBLIC_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    /*
     * 액추에이터는 8081 로 분리되어 자식 컨텍스트로 뜬다.
     * 아래 체인들이 그 포트에 적용되지 않으므로 별도 체인이 필요하다.
     * 이것이 없으면 로드 밸런서 헬스 체크와 Prometheus 스크랩이 401 을 받는다(비기능 37행, 38행).
     *
     * 인증을 요구하지 않는 것은 경계가 네트워크에 있기 때문이다.
     * 8081 은 보안 그룹이 로드 밸런서와 모니터링에게만 열어 둔다.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // API 문서는 인증 없이 연다. 인증 필터가 필요 없어 공통 배선을 쓰지 않는다
    @Bean
    @Order(PLATFORM_CHAIN_ORDER)
    public SecurityFilterChain platformFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PLATFORM_PUBLIC_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /*
     * 어느 도메인도 주장하지 않은 경로를 받는다. 기본값이 거부다 (94행).
     * 도메인 체인을 새로 추가하는 것을 잊어도 열리지 않고 막히는 쪽으로 실패한다.
     *
     * 로그인 전에 열리는 경로는 넷뿐이다. 로그인, 토큰 재발급, 점주 회원가입, 카카오 인가 코드
     * 교환이다(docs/api-spec/README.md 의 인증 절). 그 넷은 해당 도메인 체인이 permitAll 로 선언한다.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }

    /*
     * Access 와 Refresh 가 HttpOnly 쿠키로 오간다.
     * 프론트가 다른 오리진이라 쿠키를 주고받으려면 allowCredentials 와 명시적 오리진이 둘 다 필요하다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
