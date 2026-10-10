package com.launchcatch.billing.config;

import com.launchcatch.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/*
 * 정산 경로의 인가를 소유한다. 공통 배선은 ApiSecurityDefaults 가 갖는다.
 *
 * 체인이 둘인 이유는 웹훅이다. 웹훅은 결제대행사가 부르는 공개 경로라 우리 토큰이 없다.
 * 인증 체인에 넣으면 전부 401 이 되고, 거기에 permitAll 을 섞으면 같은 체인에서 어떤 경로는
 * 토큰을 보고 어떤 경로는 안 보게 되어 읽는 사람이 둘을 구별하기 어렵다.
 *
 * 경로를 하나도 빠뜨리면 안 된다. 어느 도메인도 주장하지 않은 경로는 SecurityConfig 의 기본
 * 체인이 받는데, 그 체인은 거부가 아니라 authenticated() 다. 그래서 빠뜨린 관리자 경로는
 * 막히는 대신 "로그인한 아무나" 에게 열린다. 점주 토큰으로 관리자 조회가 되는 모양이다.
 *
 * 기본 경로와 하위 경로를 둘 다 적는다. 패턴 구현에 따라 /x/** 가 /x 를 포함하는지가 갈리는데,
 * 포함하지 않는 쪽이면 기본 경로가 위 기본 체인으로 새어 역할 검사를 받지 않는다. 한 줄을
 * 더 적는 비용으로 그 가능성을 지운다.
 */
@Configuration(proxyBeanMethods = false)
public class BillingSecurityConfig {

    /** 점주 자신의 결제, 잔액, 환불 경로다. */
    private static final String[] OWNER_PATHS = {
            "/v1/owner/payments",
            "/v1/owner/payments/**",
            "/v1/owner/point-policy",
            "/v1/owner/points",
            "/v1/owner/points/**",
            "/v1/owner/refund-requests",
            "/v1/owner/refund-requests/**"
    };

    /** 관리자의 결제, 원장, 환불 처리, 정책, 조정, 대사 조회 경로다. */
    private static final String[] ADMIN_PATHS = {
            "/v1/admin/payments",
            "/v1/admin/payments/**",
            "/v1/admin/point-ledger",
            "/v1/admin/point-ledger/**",
            "/v1/admin/point-policy",
            "/v1/admin/point-policy/**",
            "/v1/admin/point-adjustments",
            "/v1/admin/refund-requests",
            "/v1/admin/refund-requests/**",
            "/v1/admin/settlement-mismatches"
    };

    private static final String WEBHOOK_PATH = "/webhook/payments";

    /*
     * 환불 승인과 포인트 조정을 SUPER_ADMIN 으로 좁힐지는 아직 정하지 않았다.
     * 좁히기로 하면 이 체인이 아니라 해당 컨트롤러 메서드의 권한 검사로 막는다. 체인을
     * 경로마다 쪼개면 돈이 움직이는 경로가 여러 파일에 흩어진다.
     */
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain billingFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        String[] paths = new String[OWNER_PATHS.length + ADMIN_PATHS.length];
        System.arraycopy(OWNER_PATHS, 0, paths, 0, OWNER_PATHS.length);
        System.arraycopy(ADMIN_PATHS, 0, paths, OWNER_PATHS.length, ADMIN_PATHS.length);

        return defaults.apply(http)
                .securityMatcher(paths)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OWNER_PATHS).hasRole("OWNER")
                        .requestMatchers(ADMIN_PATHS).hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().denyAll())
                .build();
    }

    /*
     * 결제 상태 변경 웹훅이다. 결제대행사가 부르므로 우리 토큰이 없고 인증 필터도 걸지 않는다.
     *
     * 본문을 신뢰하지 않는 것이 이 경로의 전제다. 이 웹훅은 서명 검증 수단이 없어서, 받는 쪽은
     * 본문의 상태를 그대로 쓰지 않고 결제 식별자로 결제대행사에 다시 물어 확정한다. 그래서
     * 열려 있다는 사실 자체가 위험이 되지 않는다.
     */
    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    public SecurityFilterChain billingWebhookFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(WEBHOOK_PATH)
                /*
                 * 서버 사이의 호출이라 쿠키를 쓰지 않는다.
                 * 세션도 CSRF 토큰도 둘 자리가 없다.
                 */
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
