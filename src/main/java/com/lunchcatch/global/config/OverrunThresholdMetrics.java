package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/*
 * 기준 초과를 가르는 기준 값을 지표로 낸다(#1165).
 * 06 기준 초과 대시보드와 부하 시험의 판정이 이 지표 하나로 API 와 작업마다 다른 기준을 거른다.
 * 값은 설정에서 오고 앱이 떠 있는 동안 바뀌지 않는다.
 */
@Component
@EnableConfigurationProperties(OverrunThresholdProperties.class)
public class OverrunThresholdMetrics implements MeterBinder {

    static final String METRIC = "overrun.threshold";
    // 이름별 값이 없는 것이 견줄 기본값의 이름이다
    static final String DEFAULT_NAME = "default";

    private final OverrunThresholdProperties properties;

    public OverrunThresholdMetrics(OverrunThresholdProperties properties) {
        this.properties = properties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        properties.defaults().forEach((kind, value) -> register(registry, kind, DEFAULT_NAME, value));
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
