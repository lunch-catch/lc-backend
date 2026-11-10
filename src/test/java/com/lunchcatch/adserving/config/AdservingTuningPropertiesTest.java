package com.lunchcatch.adserving.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class AdservingTuningPropertiesTest {

    // 옮기기 전의 코드 상수와 같아야 동작이 바뀌지 않는다(#1170)
    @Test
    void 아무것도_안_주면_옮기기_전의_상수와_같은_기본값이다() {
        assertThat(AdservingTuningDefaults.writeBuffer()).isEqualTo(new ImpressionWriteBufferProperties(
                10_000, 500, Duration.ofMillis(200), Duration.ofMillis(100),
                Duration.ofMillis(200), Duration.ofSeconds(5), 10));
        assertThat(AdservingTuningDefaults.serveLogBuffer()).isEqualTo(new ServeLogBufferProperties(5_000, 50));
        assertThat(AdservingTuningDefaults.dispatch()).isEqualTo(
                new ImpressionDispatchProperties(Duration.ofSeconds(1), 10, Duration.ofSeconds(30)));
        assertThat(AdservingTuningDefaults.cache()).isEqualTo(
                new AdservingCacheProperties(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    // application.yml 에 적은 기본값과 레코드의 기본값이 어긋나면 어느 쪽이 기준인지 사라진다
    @Test
    void application_yml_의_값은_레코드의_기본값과_같다() throws IOException {
        Binder binder = new Binder(ConfigurationPropertySources.from(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst()));

        assertThat(binder.bindOrCreate("adserving.impression.write-buffer", ImpressionWriteBufferProperties.class))
                .isEqualTo(AdservingTuningDefaults.writeBuffer());
        assertThat(binder.bindOrCreate("adserving.serve-log.buffer", ServeLogBufferProperties.class))
                .isEqualTo(AdservingTuningDefaults.serveLogBuffer());
        assertThat(binder.bindOrCreate("adserving.impression.dispatch", ImpressionDispatchProperties.class))
                .isEqualTo(AdservingTuningDefaults.dispatch());
        assertThat(binder.bindOrCreate("adserving.cache", AdservingCacheProperties.class))
                .isEqualTo(AdservingTuningDefaults.cache());
    }

    @Test
    void 환경변수처럼_준_값이_바인딩된다() {
        ImpressionWriteBufferProperties properties = AdservingTuningDefaults.bind("adserving.impression.write-buffer",
                ImpressionWriteBufferProperties.class, Map.of("adserving.impression.write-buffer.batch-size", "1000"));

        assertThat(properties.batchSize()).isEqualTo(1000);
        assertThat(properties.capacity()).isEqualTo(10_000);
    }

    @Test
    void 노출_저장_묶음이_용량보다_크면_거부한다() {
        assertThatThrownBy(() -> new ImpressionWriteBufferProperties(100, 101, Duration.ofMillis(200),
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(5), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void 노출_저장의_첫_백오프가_최대_백오프보다_길면_거부한다() {
        assertThatThrownBy(() -> new ImpressionWriteBufferProperties(10_000, 500, Duration.ofMillis(200),
                Duration.ofMillis(100), Duration.ofSeconds(6), Duration.ofSeconds(5), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first-backoff");
    }

    @Test
    void 노출_저장의_값이_0_이하이면_거부한다() {
        assertThatThrownBy(() -> new ImpressionWriteBufferProperties(10_000, 500, Duration.ZERO,
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(5), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-wait");
        assertThatThrownBy(() -> new ImpressionWriteBufferProperties(10_000, 500, Duration.ofMillis(200),
                Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofSeconds(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
    }

    @Test
    void 서빙_기록_묶음이_용량보다_크면_거부한다() {
        assertThatThrownBy(() -> new ServeLogBufferProperties(10, 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feeds-per-batch");
    }

    // 임대가 주기보다 짧으면 실행 사이에 임대가 풀려 다른 배치 서버가 가져간다
    @Test
    void 릴레이의_임대가_주기보다_길지_않으면_거부한다() {
        assertThatThrownBy(() -> new ImpressionDispatchProperties(Duration.ofSeconds(30), 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-ttl");
    }

    @Test
    void 캐시_기간이_0_이하이면_거부한다() {
        assertThatThrownBy(() -> new AdservingCacheProperties(Duration.ZERO, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate-max-age");
    }
}
