package com.launchcatch.coupon;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CouponSecurityConfig {

    private static final String[] COUPON_PATHS = {
            "/v1/coupons/**"
    };

    private static final String ISSUANCE_LIMIT_PATH = "/v1/coupons/issuance-limit";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain couponFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(COUPON_PATHS)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ISSUANCE_LIMIT_PATH)
                        .hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
