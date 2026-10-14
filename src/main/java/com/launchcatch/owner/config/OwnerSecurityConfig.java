package com.launchcatch.owner.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class OwnerSecurityConfig {
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain ownerAuthFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/owner/auth/tokens", "/v1/owner/auth/tokens:refresh", "/v1/owners",
                        "/v1/owner/me", "/v1/owner/kakao-places")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/owner/auth/tokens", "/v1/owner/auth/tokens:refresh", "/v1/owners")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/owner/me", "/v1/owner/kakao-places").hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
}
