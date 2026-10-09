package com.launchcatch.admin.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdminLoginSecurityConfig {
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain adminLoginSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/admin/auth/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/admin/auth/tokens").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/v1/admin/auth/tokens").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
