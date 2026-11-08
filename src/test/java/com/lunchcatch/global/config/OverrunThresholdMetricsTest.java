package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class OverrunThresholdMetricsTest {

    @Test
    @DisplayName("설정의 값마다 종류와 이름을 단 게이지를 초 단위로 낸다")
    void 값마다_게이지를_낸다() {
        OverrunThresholdProperties properties = new OverrunThresholdProperties(
                Map.of(),
                Map.of("GET /v1/feed", Duration.ofMillis(300)),
                Map.of("adserving.impression", Duration.ofSeconds(5)),
                Map.of(),
                Map.of(),
                Map.of("transaction", Duration.ofSeconds(1)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OverrunThresholdMetrics(properties).bindTo(registry);

        assertThat(value(registry, "api", "GET /v1/feed")).isEqualTo(0.3);
        assertThat(value(registry, "relay", "adserving.impression")).isEqualTo(5.0);
        assertThat(value(registry, "global", "transaction")).isEqualTo(1.0);
        assertThat(registry.find(OverrunThresholdMetrics.METRIC).gauges()).hasSize(3);
    }

    @Test
    @DisplayName("Prometheus 에는 overrun_threshold_seconds 로 나간다")
    void Prometheus_이름() {
        OverrunThresholdProperties properties = new OverrunThresholdProperties(
                Map.of(), Map.of("GET /v1/feed", Duration.ofMillis(300)), Map.of(), Map.of(), Map.of(), Map.of());
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new OverrunThresholdMetrics(properties).bindTo(registry);

        assertThat(registry.scrape()).contains("overrun_threshold_seconds{kind=\"api\",name=\"GET /v1/feed\"} 0.3");
    }

    @Test
    @DisplayName("종류마다의 기본값은 이름 default 로 낸다")
    void 기본값은_default_로_낸다() {
        OverrunThresholdProperties properties = new OverrunThresholdProperties(
                Map.of("worker", Duration.ofSeconds(5), "job", Duration.ofMinutes(10)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OverrunThresholdMetrics(properties).bindTo(registry);

        assertThat(value(registry, "worker", OverrunThresholdMetrics.DEFAULT_NAME)).isEqualTo(5.0);
        assertThat(value(registry, "job", OverrunThresholdMetrics.DEFAULT_NAME)).isEqualTo(600.0);
    }

    @Test
    @DisplayName("0 이하의 기준은 앱이 뜰 때 막는다")
    void 영_이하는_막는다() {
        assertThatThrownBy(() -> new OverrunThresholdProperties(
                Map.of(), Map.of("GET /v1/feed", Duration.ZERO), Map.of(), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET /v1/feed");
    }

    @Test
    @DisplayName("기본값을 둘 수 없는 종류의 기본값은 앱이 뜰 때 막는다")
    void 모르는_기본값_종류는_막는다() {
        assertThatThrownBy(() -> new OverrunThresholdProperties(
                Map.of("global", Duration.ofSeconds(1)), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global");
    }

    @Test
    @DisplayName("application.yml 의 기준이 대괄호 키 그대로 묶인다")
    void application_yml_을_묶는다() throws Exception {
        Binder binder = new Binder(ConfigurationPropertySources.from(applicationYml()));

        OverrunThresholdProperties properties = binder
                .bind(OverrunThresholdProperties.PREFIX, OverrunThresholdProperties.class)
                .get();

        assertThat(properties.api()).containsEntry("GET /v1/feed", Duration.ofMillis(300));
        assertThat(properties.relay()).containsEntry("adserving.impression", Duration.ofSeconds(5));
        assertThat(properties.global()).containsEntry("transaction", Duration.ofSeconds(1));
        assertThat(properties.defaults()).containsOnlyKeys("api", "relay", "worker", "job");
    }

    @Test
    @DisplayName("설정의 값이 틀리면 묶을 때 실패한다")
    void 틀린_값은_묶지_못한다() {
        Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource(
                "test", Map.of(OverrunThresholdProperties.PREFIX + ".api.[GET /v1/feed]", "-1s"))));

        assertThatThrownBy(() -> binder.bind(OverrunThresholdProperties.PREFIX, OverrunThresholdProperties.class))
                .isInstanceOf(BindException.class);
    }

    private static double value(SimpleMeterRegistry registry, String kind, String name) {
        Gauge gauge = registry.find(OverrunThresholdMetrics.METRIC).tag("kind", kind).tag("name", name).gauge();
        assertThat(gauge).as("%s %s", kind, name).isNotNull();
        return gauge.value();
    }

    private static Iterable<PropertySource<?>> applicationYml() throws Exception {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    }
}
