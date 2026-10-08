package com.launchcatch.admin.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminRegistrationSecurityConfig {
    @Bean
    // 넓은 관리자 체인보다 먼저 계정 발급 전용 체인을 선택한다.
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER - 10)
    public SecurityFilterChain adminRegistrationSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/admin/admins", "/v1/admin/admins/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/admin/admins").hasRole("SUPER_ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}