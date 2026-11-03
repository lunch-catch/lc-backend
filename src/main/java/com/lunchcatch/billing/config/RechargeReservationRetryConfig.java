package com.lunchcatch.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 재예약 재시도 설정을 빈으로 올린다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RechargeReservationRetryProperties.class)
public class RechargeReservationRetryConfig {
}
