package com.launchcatch.store.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class StoreSecurityConfig {
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain storeFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http.securityMatcher("/v1/stores"))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MEMBER"))
                .build();
    }
}
