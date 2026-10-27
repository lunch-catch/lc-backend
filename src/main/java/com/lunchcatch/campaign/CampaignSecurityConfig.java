package com.lunchcatch.campaign;

import com.lunchcatch.auth.ApiSecurityDefaults;
import java.util.Arrays;
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
public class CampaignSecurityConfig {

    private static final String[] ADMIN_PATHS = {
            "/v1/admin/templates/**",
            "/v1/admin/campaigns",
            "/v1/admin/campaigns/**"
    };

    /** 점주의 캠페인 등록 경로다. 기본 경로와 하위 경로를 둘 다 적는다(billing과 같은 이유). */
    private static final String[] OWNER_PATHS = {
            "/v1/owner/campaigns",
            "/v1/owner/campaigns/**",
            "/v1/owner/templates"
    };

    /** 가게 상세 화면이 부르는 사용자용 포스터 조회다(#495). */
    private static final String MEMBER_POSTER_PATH = "/v1/campaigns/{campaignId}/poster";

    private static final String[] MEMBER_PATHS = {
            MEMBER_POSTER_PATH
    };

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain campaignFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        String[] paths = new String[ADMIN_PATHS.length + OWNER_PATHS.length + MEMBER_PATHS.length];
        System.arraycopy(ADMIN_PATHS, 0, paths, 0, ADMIN_PATHS.length);
        System.arraycopy(OWNER_PATHS, 0, paths, ADMIN_PATHS.length, OWNER_PATHS.length);
        System.arraycopy(MEMBER_PATHS, 0, paths, ADMIN_PATHS.length + OWNER_PATHS.length, MEMBER_PATHS.length);

        return defaults.apply(http)
                .securityMatcher(new AndRequestMatcher(
                        new OrRequestMatcher(Arrays.stream(paths)
                                .<RequestMatcher>map(PathPatternRequestMatcher::pathPattern).toList()),
                        new NegatedRequestMatcher(PathPatternRequestMatcher.pathPattern(
                                "/v1/owner/campaigns/*/analytics/funnel"))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ADMIN_PATHS).hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(OWNER_PATHS).hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, MEMBER_POSTER_PATH).hasRole("MEMBER")
                        .anyRequest().denyAll())
                .build();
    }
}
