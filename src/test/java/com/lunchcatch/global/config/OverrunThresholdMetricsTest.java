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
    @DisplayName("API 마다의 목표와 기본값을 kind api 의 게이지로 초 단위로 낸다")
    void 목표와_기본값을_낸다() {
        OverrunThresholdProperties properties = new OverrunThresholdProperties(
                Duration.ofMillis(500), Map.of("GET /v1/feed", Duration.ofMillis(300)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OverrunThresholdMetrics(properties).bindTo(registry);

        assertThat(value(registry, "GET /v1/feed")).isEqualTo(0.3);
        assertThat(value(registry, OverrunThresholdProperties.DEFAULT_NAME)).isEqualTo(0.5);
        assertThat(registry.find(OverrunThresholdMetrics.METRIC).gauges()).hasSize(2);
    }

    @Test
    @DisplayName("Prometheus 에는 overrun_threshold_seconds 로 나간다")
    void Prometheus_이름() {
        OverrunThresholdProperties properties = new OverrunThresholdProperties(
                Duration.ofMillis(500), Map.of("GET /v1/feed", Duration.ofMillis(300)));
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new OverrunThresholdMetrics(properties).bindTo(registry);

        assertThat(registry.scrape()).contains("overrun_threshold_seconds{kind=\"api\",name=\"GET /v1/feed\"} 0.3");
    }

    @Test
    @DisplayName("0 이하의 목표는 앱이 뜰 때 막는다")
    void 영_이하는_막는다() {
        assertThatThrownBy(() -> new OverrunThresholdProperties(Duration.ofMillis(500), Map.of("GET /v1/feed", Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET /v1/feed");
        assertThatThrownBy(() -> new OverrunThresholdProperties(Duration.ofMillis(-1), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api-default");
    }

    @Test
    @DisplayName("기본값이 빠지면 앱이 뜰 때 막는다")
    void 기본값이_없으면_막는다() {
        assertThatThrownBy(() -> new OverrunThresholdProperties(null, Map.of("GET /v1/feed", Duration.ofMillis(300))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api-default");
    }

    @Test
    @DisplayName("API 의 키로 default 를 쓰면 기본값과 겹쳐 앱이 뜰 때 막는다")
    void default_키는_막는다() {
        assertThatThrownBy(() -> new OverrunThresholdProperties(
                Duration.ofMillis(500), Map.of(OverrunThresholdProperties.DEFAULT_NAME, Duration.ofMillis(300))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api-default");
    }

    @Test
    @DisplayName("application.yml 의 목표가 대괄호 키 그대로 묶인다")
    void application_yml_을_묶는다() throws Exception {
        Binder binder = new Binder(ConfigurationPropertySources.from(applicationYml()));

        OverrunThresholdProperties properties = binder
                .bind(OverrunThresholdProperties.PREFIX, OverrunThresholdProperties.class)
                .get();

        assertThat(properties.apiDefault()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.api()).containsEntry("GET /v1/feed", Duration.ofMillis(300));
    }

    @Test
    @DisplayName("설정의 값이 틀리면 묶을 때 실패한다")
    void 틀린_값은_묶지_못한다() {
        Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("test", Map.of(
                OverrunThresholdProperties.PREFIX + ".api-default", "500ms",
                OverrunThresholdProperties.PREFIX + ".api.[GET /v1/feed]", "-1s"))));

        assertThatThrownBy(() -> binder.bind(OverrunThresholdProperties.PREFIX, OverrunThresholdProperties.class))
                .isInstanceOf(BindException.class);
    }

    private static double value(SimpleMeterRegistry registry, String name) {
        Gauge gauge = registry.find(OverrunThresholdMetrics.METRIC).tag("kind", "api").tag("name", name).gauge();
        assertThat(gauge).as(name).isNotNull();
        return gauge.value();
    }

    private static Iterable<PropertySource<?>> applicationYml() throws Exception {
        return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    }
}
