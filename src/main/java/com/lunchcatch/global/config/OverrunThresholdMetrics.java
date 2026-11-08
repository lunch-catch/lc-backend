package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * API 마다의 p99 목표를 지표로 낸다(#1165).
 * 06 기준 초과 대시보드와 부하 시험의 판정이 이 지표로 API 마다 다른 목표를 거른다.
 * 릴레이, 워커, cron 의 기준은 lc-infra 의 규칙 파일이 같은 이름에 다른 kind 로 낸다.
 */
@Component
@EnableConfigurationProperties(OverrunThresholdProperties.class)
public class OverrunThresholdMetrics implements MeterBinder {

    static final String METRIC = "overrun.threshold";
    static final String KIND = "api";

    private final OverrunThresholdProperties properties;

    public OverrunThresholdMetrics(OverrunThresholdProperties properties) {
        this.properties = properties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        register(registry, OverrunThresholdProperties.DEFAULT_NAME, properties.apiDefault());
        properties.api().forEach((name, value) -> register(registry, name, value));
    }

    private static void register(MeterRegistry registry, String name, Duration value) {
        double seconds = value.toNanos() / 1e9;
        Gauge.builder(METRIC, () -> seconds)
                .description("기준 초과를 가르는 기준")
                .baseUnit("seconds")
                .tag("kind", KIND)
                .tag("name", name)
                .register(registry);
    }
}
