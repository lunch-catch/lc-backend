package com.launchcatch.store.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class OwnerStoreSecurityConfig {
    // 점주용 가게 기본 정보·위치·영업시간 저장 API는 OWNER 권한이 있는 점주만 접근할 수 있다.
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain ownerStoreInfoFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http.securityMatcher("/v1/owner/store/basic", "/v1/owner/store/location", "/v1/owner/store/hours"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.PUT, "/v1/owner/store/basic", "/v1/owner/store/location", "/v1/owner/store/hours").hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain ownerStoreOnboardingFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http.securityMatcher("/v1/owner/onboarding"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/v1/owner/onboarding").hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
}
