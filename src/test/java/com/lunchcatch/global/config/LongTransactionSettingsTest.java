package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.PropertiesMeterFilter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

class LongTransactionSettingsTest {

    private static final String LEAK_DETECTION = "spring.datasource.hikari.leak-detection-threshold";

    // 끊는 장치가 아니라 오래 쥔 자리의 스택을 경고로 남기는 진단이다. 품질 문서 성능 효율성의 커넥션 누수 탐지 5000ms 다
    @Test
    @DisplayName("앱은 커넥션을 5초 넘게 쥐면 누수 경고를 남긴다")
    void 앱은_5초_넘게_쥔_커넥션을_경고한다() {
        assertThat(resolved().getProperty(LEAK_DETECTION)).isEqualTo("5000");
    }

    // 배치는 00:00 묶음을 트랜잭션으로 감싸지 않아 오래 쥐는 것이 정상이 아니라 앱의 값을 그대로 쓴다(#1167)
    @Test
    @DisplayName("batch 프로필을 켜도 누수 경고 문턱은 5초다")
    void 배치도_5초_넘게_쥔_커넥션을_경고한다() {
        assertThat(resolved("batch").getProperty(LEAK_DETECTION)).isEqualTo("5000");
    }

    // 버킷 경계가 품질 문서의 두 기준(100ms 이하, 상한 1초)과 맞아야 넘은 수를 근사 없이 센다
    @Test
    @DisplayName("커넥션 사용 시간을 0.1초와 1초 경계의 히스토그램으로 낸다")
    void 커넥션_사용_시간을_두_기준의_경계로_센다() throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new PropertiesMeterFilter(metricsProperties()));
        Timer usage = Timer.builder("hikaricp.connections.usage").register(registry);

        usage.record(Duration.ofMillis(50));
        usage.record(Duration.ofMillis(500));
        usage.record(Duration.ofMillis(2000));

        CountAtBucket[] buckets = usage.takeSnapshot().histogramCounts();
        assertThat(countAt(buckets, Duration.ofMillis(100))).isEqualTo(1);
        assertThat(countAt(buckets, Duration.ofSeconds(1))).isEqualTo(2);
    }

    // 앱이 뜰 때와 같은 순서로 application.yml 과 프로필 파일의 모든 문서를 읽어 겹친 결과다
    private static ConfigurableEnvironment resolved(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(),
                new DefaultBootstrapContext(), profiles);
        return environment;
    }

    private static PropertySource<?> load(String name) throws IOException {
        return new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).getFirst();
    }

    private static MetricsProperties metricsProperties() throws IOException {
        Binder binder = new Binder(ConfigurationPropertySources.from(load("application.yml")));
        return binder.bind("management.metrics", MetricsProperties.class).orElseGet(MetricsProperties::new);
    }

    private static double countAt(CountAtBucket[] buckets, Duration boundary) {
        double nanos = boundary.toNanos();
        return Arrays.stream(buckets)
                .filter(bucket -> bucket.bucket(TimeUnit.NANOSECONDS) == nanos)
                .findFirst()
                .map(CountAtBucket::count)
                .orElseThrow(() -> new AssertionError(boundary + " 경계의 버킷이 없다"));
    }
}
