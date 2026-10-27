package com.lunchcatch.billing.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lunchcatch.billing.dto.PlanSnapshot;
import com.lunchcatch.billing.dto.PlanSnapshotKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 그날 계획 스냅샷 캐시를 만든다. 캐시는 여기서 만들고 서비스는 주입받아 쓴다
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanSnapshotCacheProperties.class)
public class PlanSnapshotCacheConfig {

    @Bean
    public Cache<PlanSnapshotKey, PlanSnapshot> planSnapshotCache(PlanSnapshotCacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfterWrite(properties.expireAfterWrite())
                .build();
    }
}
