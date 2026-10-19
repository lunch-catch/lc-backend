package com.launchcatch.ops.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OpsSecurityConfig {

    private static final String[] ADMIN_PATHS = {
            "/v1/admin/batch-executions/**"
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain opsFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(ADMIN_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATHS).hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
