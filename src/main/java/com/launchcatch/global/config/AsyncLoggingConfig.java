package com.launchcatch.global.config;

import com.launchcatch.global.logging.HttpBodyLoggingFilter;
import com.launchcatch.global.logging.MdcLoggingFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// 비동기 응답의 최종 디스패치에도 본문 복사와 기존 추적 ID를 적용한다.
@Configuration
public class AsyncLoggingConfig {

    @Bean
    FilterRegistrationBean<MdcLoggingFilter> mdcLoggingRegistration(MdcLoggingFilter filter) {
        return registration(filter, Ordered.HIGHEST_PRECEDENCE);
    }

    @Bean
    FilterRegistrationBean<HttpBodyLoggingFilter> bodyLoggingRegistration(HttpBodyLoggingFilter filter) {
        return registration(filter, Ordered.HIGHEST_PRECEDENCE + 1);
    }

    private <T extends Filter> FilterRegistrationBean<T> registration(T filter, int order) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setAsyncSupported(true);
        registration.setOrder(order);
        return registration;
    }
}
