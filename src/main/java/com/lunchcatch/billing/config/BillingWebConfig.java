package com.lunchcatch.billing.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class BillingWebConfig implements WebMvcConfigurer {

    private final ChargeRateLimitInterceptor chargeRateLimitInterceptor;
    private final OwnerActiveInterceptor ownerActiveInterceptor;

    // 등록한 순서대로 돈다. 충전 상한이 DB 를 읽는 상태 검사보다 앞이어야 한다
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(chargeRateLimitInterceptor).addPathPatterns(ChargeRateLimitInterceptor.PATHS);
        registry.addInterceptor(ownerActiveInterceptor).addPathPatterns(BillingSecurityConfig.OWNER_PATHS);
    }
}
