package com.lunchcatch.owner.config;

import com.lunchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class OwnerTermsSecurityConfig {
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain ownerTermsFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/owner/terms-agreements", "/v1/owner/terms-agreements/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PUT, "/v1/owner/terms-agreements").hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
}