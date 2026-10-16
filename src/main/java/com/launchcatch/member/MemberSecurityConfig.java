package com.launchcatch.member;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class MemberSecurityConfig {

    private static final RequestMatcher MEMBER_API_PATHS = new AndRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/v1/members/**"),
            new NegatedRequestMatcher(PathPatternRequestMatcher.pathPattern("/v1/members/me/usage-stats")));

    private static final RequestMatcher SECURITY_MATCHER = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern("/v1/auth/**"),
            MEMBER_API_PATHS,
            PathPatternRequestMatcher.pathPattern("/v1/member-profiles/**"),
            PathPatternRequestMatcher.pathPattern("/webhook/kakao/**"));

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain memberFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(SECURITY_MATCHER)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/v1/auth/kakao/authorize").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/tokens", "/v1/auth/tokens:refresh").permitAll()
                        .requestMatchers("/webhook/kakao/**").permitAll()
                        .requestMatchers("/v1/auth/**", "/v1/members/**", "/v1/member-profiles/**")
                        .hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
