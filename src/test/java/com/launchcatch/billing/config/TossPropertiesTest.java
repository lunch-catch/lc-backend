package com.launchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TossPropertiesTest {

    @Test
    void 비밀키만_주면_주소와_타임아웃은_시작값으로_채워진다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("billing.toss.secret-key", "sk")));

        TossProperties properties = binder.bind("billing.toss", TossProperties.class).get();

        assertThat(properties.baseUrl()).isEqualTo("https://api.tosspayments.com");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 타임아웃은_설정으로_바꾼다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "billing.toss.secret-key", "sk",
                "billing.toss.connect-timeout", "1s",
                "billing.toss.read-timeout", "20s")));

        TossProperties properties = binder.bind("billing.toss", TossProperties.class).get();

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 문자열로_찍어도_비밀키가_드러나지_않는다() {
        TossProperties properties =
                new TossProperties("https://api.tosspayments.com", "test_sk_SECRET", Duration.ofSeconds(3), Duration.ofSeconds(10));

        assertThat(properties.toString()).doesNotContain("test_sk_SECRET").contains("****");
    }
}
