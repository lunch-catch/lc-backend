package com.launchcatch.billing.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class BillingWebConfig implements WebMvcConfigurer {

    private final OwnerActiveInterceptor ownerActiveInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ownerActiveInterceptor).addPathPatterns(BillingSecurityConfig.OWNER_PATHS);
    }
}
