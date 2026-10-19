package com.launchcatch.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 부정 경고 임계값을 빈으로 올린다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsFraudProperties.class)
public class AnalyticsFraudConfig {
}
