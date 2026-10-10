package com.launchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class TossCircuitBreakerConfigTest {

    private static WebClientResponseException responseOf(int status) {
        return WebClientResponseException.create(HttpStatusCode.valueOf(status), "x", HttpHeaders.EMPTY,
                new byte[0], null);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void 서버_오류와_429_는_실패로_센다(int status) {
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(responseOf(status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void 그_밖의_4xx_는_토스가_정상으로_거절한_응답이라_세지_않는다(int status) {
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(responseOf(status))).isFalse();
    }

    @Test
    void 응답을_못_받은_전송_단계_예외는_실패로_센다() {
        Throwable transport = new WebClientRequestException(new ConnectException("refused"), HttpMethod.POST,
                URI.create("https://api.tosspayments.com/v1/payments/confirm"), HttpHeaders.EMPTY);

        assertThat(TossCircuitBreakerConfig.isCircuitFailure(transport)).isTrue();
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(new TimeoutException("t"))).isTrue();
    }

    @Test
    void 다른_예외로_감싸여_있어도_원인_사슬을_따라가_판정한다() {
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(new RuntimeException(responseOf(503)))).isTrue();
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(new RuntimeException(responseOf(400)))).isFalse();
    }

    @Test
    void 우리_코드의_버그는_PG_장애로_세지_않는다() {
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(new NullPointerException())).isFalse();
        assertThat(TossCircuitBreakerConfig.isCircuitFailure(new IllegalStateException("매핑 오류"))).isFalse();
    }
}
