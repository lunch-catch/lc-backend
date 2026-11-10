package com.lunchcatch.adserving.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 부하 시험으로 바꿔 볼 버퍼, 릴레이, 캐시의 조정 값을 빈으로 올린다(#1170)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ImpressionWriteBufferProperties.class, ServeLogBufferProperties.class,
        ImpressionDispatchProperties.class, AdservingCacheProperties.class})
public class AdservingTuningConfig {
}
