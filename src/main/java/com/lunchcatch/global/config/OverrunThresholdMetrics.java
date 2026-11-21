package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * 기준 초과를 가르는 기준을 지표로 낸다(#1165, #1173).
 * 06 처리 시간 목표 초과 대시보드와 부하 시험의 판정이 이 지표로 API 와 작업마다 다른 기준을 거른다.
 */
@Component
@EnableConfigurationProperties(OverrunThresholdProperties.class)
public class OverrunThresholdMetrics implements MeterBinder {

    static final String METRIC = "overrun.threshold";

    private final OverrunThresholdProperties properties;

    public OverrunThresholdMetrics(OverrunThresholdProperties properties) {
        this.properties = properties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        properties.byKind().forEach((kind, values) ->
                values.forEach((name, value) -> register(registry, kind, name, value)));
    }

    private static void register(MeterRegistry registry, String kind, String name, Duration value) {
        double seconds = value.toNanos() / 1e9;
        Gauge.builder(METRIC, () -> seconds)
                .description("기준 초과를 가르는 기준")
                .baseUnit("seconds")
                .tag("kind", kind)
                .tag("name", name)
                .register(registry);
    }
}
