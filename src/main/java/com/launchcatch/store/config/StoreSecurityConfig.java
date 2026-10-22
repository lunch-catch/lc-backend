package com.launchcatch.store.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class StoreSecurityConfig {

    // 점주의 이미지, 메뉴 등록 경로다(#375, #389).
    private static final String[] OWNER_PATHS = {
            "/v1/owner/store-images:prepareUpload",
            "/v1/owner/store-images:confirmUpload",
            "/v1/owner/store-images:complete",
            "/v1/owner/store-menus",
            "/v1/owner/store-menus:complete",
            "/v1/owner/store-menu-images:prepareUpload",
            "/v1/owner/store-menu-images:confirmUpload"
    };

    // 사용자용 가게 조회 API는 MEMBER 권한이 있는 사용자만 접근할 수 있다.
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain storeFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http.securityMatcher("/v1/stores", "/v1/stores/{storeId}"))
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
