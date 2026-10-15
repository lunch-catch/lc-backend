package com.launchcatch.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 서빙 시각과 가중치 설정을 빈으로 올린다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingScheduleProperties.class)
public class BillingScheduleConfig {
}
