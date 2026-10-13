package com.launchcatch.adserving.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * 광고 서빙 경로의 인가를 소유한다. 공통 배선은 ApiSecurityDefaults 가 갖는다.
 * 지금은 GET /v1/feed 하나다. 나머지 경로는 그 API 를 구현할 때 더한다(feed-api-spec.md 의 목록).
 */
@Configuration(proxyBeanMethods = false)
public class AdservingSecurityConfig {

    private static final String FEED_PATH = "/v1/feed";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain adservingFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http)
                .securityMatcher(FEED_PATH)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, FEED_PATH).hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
