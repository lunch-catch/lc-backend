package com.launchcatch.member;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class MemberSecurityConfig {

    private static final String[] MEMBER_PATHS = {
            "/v1/auth/**",
            "/v1/members/**",
            "/v1/member-profiles/**",
            "/webhook/kakao/**"
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain memberFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(MEMBER_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/v1/auth/kakao/authorize").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/tokens", "/v1/auth/tokens:refresh").permitAll()
                        .requestMatchers("/webhook/kakao/**").permitAll()
                        .requestMatchers("/v1/auth/**", "/v1/members/**", "/v1/member-profiles/**")
                        .hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
