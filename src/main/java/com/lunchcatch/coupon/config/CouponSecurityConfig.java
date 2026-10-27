package com.lunchcatch.coupon.config;

import com.lunchcatch.auth.ApiSecurityDefaults;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class CouponSecurityConfig {

    private static final String OWNER_USAGE_PATH = "/v1/owner/stores/{storeId}/coupon-usages";
    private static final String OWNER_DAILY_USAGE_PATH = "/v1/owner/stores/{storeId}/coupon-usages/daily";
    private static final String OWNER_REDEEM_PATH = "/v1/owner/coupon-usages";
    private static final String OWNER_SUMMARY_PATH = "/v1/owner/campaigns/{campaignId}/coupons/summary";
    private static final String EVENT_STATUS_PATH = "/v1/coupon-events:getStatus";

    private static final String[] COUPON_PATHS = {
            "/v1/coupons/**",
            EVENT_STATUS_PATH,
            OWNER_USAGE_PATH,
            OWNER_DAILY_USAGE_PATH,
            OWNER_REDEEM_PATH,
            OWNER_SUMMARY_PATH
    };

    private static final String LIST_PATH = "/v1/coupons";
    private static final String ISSUANCE_LIMIT_PATH = "/v1/coupons/issuance-limit";
    private static final String SUMMARY_PATH = "/v1/coupons/summary";
    private static final String DETAIL_PATH = "/v1/coupons/{memberCouponId}";
    private static final String QR_PATH = "/v1/coupons/{memberCouponId}:generateQr";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain couponFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(COUPON_PATHS)
                .authorizeHttpRequests(auth -> auth
                        // 최초 MEMBER 인증을 통과한 발급 결과의 재디스패치만 허용한다.
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.ASYNC
                                && "POST".equals(request.getMethod()) && LIST_PATH.equals(request.getRequestURI()))
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, LIST_PATH, ISSUANCE_LIMIT_PATH, SUMMARY_PATH, DETAIL_PATH)
                        .hasRole("MEMBER")
                        .requestMatchers(HttpMethod.DELETE, DETAIL_PATH)
                        .hasRole("MEMBER")
                        .requestMatchers(HttpMethod.GET, OWNER_USAGE_PATH).hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, OWNER_DAILY_USAGE_PATH).hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, OWNER_SUMMARY_PATH).hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, OWNER_REDEEM_PATH).hasRole("OWNER")
                        .requestMatchers(HttpMethod.POST, QR_PATH, EVENT_STATUS_PATH, LIST_PATH)
                        .hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
