package com.launchcatch.campaign;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CampaignSecurityConfig {

    private static final String[] CAMPAIGN_PATHS = {
            "/v1/admin/templates/**"
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain campaignFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(CAMPAIGN_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/admin/templates/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().denyAll())
                .build();
    }
}
