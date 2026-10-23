package com.launchcatch.adserving.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 진행률 계산의 시간대별 가중치 설정을 빈으로 올린다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PacingProperties.class)
public class PacingConfig {
}
