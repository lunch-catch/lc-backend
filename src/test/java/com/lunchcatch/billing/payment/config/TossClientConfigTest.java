package com.lunchcatch.billing.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.global.config.HttpClientLatencyHistogramHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class TossClientConfigTest {

    // 배포 뒤 첫 승인 호출이 실패해도 실패율에 잡히려면 호출 전에 0 이 있어야 한다
    @Test
    void 토스_WebClient를_만들_때_토스_호스트의_실패_수를_0으로_만든다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TossProperties properties = new TossProperties("https://api.tosspayments.com", "sk",
                Duration.ofSeconds(3), Duration.ofSeconds(10));

        new TossClientConfig().tossPaymentWebClient(WebClient.builder(), properties,
                new HttpClientLatencyHistogramHandler(registry));

        assertThat(registry.get("http.client.failures").tag("client.name", "api.tosspayments.com").counter().count())
                .isZero();
    }
}
