package com.launchcatch.adserving.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AdservingSecurityConfig {

    private static final String FEED_PATH = "/v1/feed";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain adservingFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(FEED_PATH)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, FEED_PATH).hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
