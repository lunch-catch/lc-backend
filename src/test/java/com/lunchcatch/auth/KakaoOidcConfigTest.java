package com.lunchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.global.config.HttpClientLatencyHistogramHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class KakaoOidcConfigTest {

    // 배포 뒤 첫 로그인 호출이 실패해도 실패율에 잡히려면 호출 전에 0 이 있어야 한다
    @Test
    void 카카오_WebClient를_만들_때_인증과_API_호스트의_실패_수를_0으로_만든다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new KakaoOidcConfig().kakaoApiWebClient(WebClient.builder(), new HttpClientLatencyHistogramHandler(registry));

        assertThat(registry.get("http.client.failures").tag("client.name", "kauth.kakao.com").counter().count()).isZero();
        assertThat(registry.get("http.client.failures").tag("client.name", "kapi.kakao.com").counter().count()).isZero();
    }
}
