package com.launchcatch.analytics.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AnalyticsSecurityConfig {

    private static final String[] ADMIN_PATHS = {
            "/v1/admin/dashboard/**",
            "/v1/admin/aggregations:recalculate",
            "/v1/admin/aggregation-runs",
            "/v1/admin/sales",
            "/v1/admin/sales/**",
            "/v1/admin/fraud/**",
            "/v1/admin/campaign-reports"
    };

    private static final String[] MEMBER_PATHS = {
            "/v1/members/me/usage-stats",
            "/v1/events"
    };

    private static final String OWNER_FUNNEL_PATH = "/v1/owner/campaigns/*/analytics/funnel";

    private static final String[] ANALYTICS_PATHS = {
            "/v1/admin/dashboard/**",
            "/v1/admin/aggregations:recalculate",
            "/v1/admin/aggregation-runs",
            "/v1/admin/sales",
            "/v1/admin/sales/**",
            "/v1/admin/fraud/**",
            "/v1/admin/campaign-reports",
            "/v1/members/me/usage-stats",
            "/v1/events",
            OWNER_FUNNEL_PATH
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain analyticsFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(ANALYTICS_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATHS).hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(MEMBER_PATHS).hasRole("MEMBER")
                        .requestMatchers(OWNER_FUNNEL_PATH).hasRole("OWNER")
                        .anyRequest().denyAll())
                .build();
    }
}
