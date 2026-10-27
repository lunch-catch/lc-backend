package com.lunchcatch.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 결제 재조회 설정을 빈으로 올린다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentRecheckProperties.class)
public class PaymentRecheckConfig {
}
