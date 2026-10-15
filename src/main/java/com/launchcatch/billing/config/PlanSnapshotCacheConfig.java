package com.launchcatch.billing.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.dto.PlanSnapshotKey;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 그날 계획 스냅샷 캐시와 시간대별 목표 캐시를 만든다. 캐시는 여기서 만들고 서비스는 주입받아 쓴다.
 * 두 캐시는 같은 설정값을 쓴다. 키가 영업일 단위로 갈리거나 계획 행마다 하나라서 크기와 수명이 비슷하다.
 */
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

    // 키는 계획 행 ID, 값은 시(hour)에서 목표 금액으로 가는 맵이다. 목표 행이 없는 계획은 넣지 않는다
    @Bean
    public Cache<Long, Map<Integer, Long>> hourlyTargetCache(PlanSnapshotCacheProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(properties.maximumSize())
                .expireAfterWrite(properties.expireAfterWrite())
                .build();
    }
}
