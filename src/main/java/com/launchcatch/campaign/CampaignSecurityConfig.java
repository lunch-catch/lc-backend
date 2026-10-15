package com.launchcatch.campaign;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CampaignSecurityConfig {

    private static final String[] ADMIN_PATHS = {
            "/v1/admin/templates/**"
    };

    /** 점주의 캠페인 등록 경로다. 기본 경로와 하위 경로를 둘 다 적는다(billing과 같은 이유). */
    private static final String[] OWNER_PATHS = {
            "/v1/owner/campaigns",
            "/v1/owner/campaigns/**"
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain campaignFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        String[] paths = new String[ADMIN_PATHS.length + OWNER_PATHS.length];
        System.arraycopy(ADMIN_PATHS, 0, paths, 0, ADMIN_PATHS.length);
        System.arraycopy(OWNER_PATHS, 0, paths, ADMIN_PATHS.length, OWNER_PATHS.length);

        return defaults.apply(http)
                .securityMatcher(paths)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATHS).hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(OWNER_PATHS).hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
}
