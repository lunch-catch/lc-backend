package com.launchcatch.store.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class StoreSecurityConfig {

    // 점주의 가게 등록 진행 상태와 이미지 등록 경로다(#375).
    private static final String[] OWNER_PATHS = {
            "/v1/owner/store-registration-progress",
            "/v1/owner/store-images:prepareUpload",
            "/v1/owner/store-images:confirmUpload",
            "/v1/owner/store-images:complete"
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain storeFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http.securityMatcher("/v1/stores"))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("MEMBER"))
                .build();
    }

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain storeOwnerFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(OWNER_PATHS)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("OWNER"))
                .build();
    }
}
