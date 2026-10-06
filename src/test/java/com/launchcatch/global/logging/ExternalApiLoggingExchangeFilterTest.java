package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

class ExternalApiLoggingExchangeFilterTest {

    private LogCapture logs;

    @BeforeEach
    void attachLogs() {
        logs = LogCapture.attach(ExternalApiLoggingExchangeFilter.class);
    }

    @AfterEach
    void detachLogs() {
        logs.close();
    }

    private ClientRequest request(String url) {
        return ClientRequest.create(HttpMethod.POST, URI.create(url)).build();
    }

    private ExchangeFunction respondingWith(HttpStatus status) {
        return request -> Mono.just(ClientResponse.create(status).build());
    }

    @Test
    void 성공한_호출은_메서드_경로_상태코드를_INFO로_남긴다() {
        // given
        ClientRequest request = request("http://localhost/oauth/token");

        // when
        ExternalApiLoggingExchangeFilter.logCalls().filter(request, respondingWith(HttpStatus.OK)).block();

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .startsWith("event=EXTERNAL_API_CALL method=POST path=/oauth/token status=200 durationMs=");
    }

    @Test
    void 오류_상태코드도_응답이_왔으면_호출_로그에_상태코드를_남긴다() {
        // given
        ClientRequest request = request("http://localhost/oauth/token");

        // when
        ExternalApiLoggingExchangeFilter.logCalls()
                .filter(request, respondingWith(HttpStatus.INTERNAL_SERVER_ERROR)).block();

        // then
        assertThat(logs.only().getFormattedMessage()).contains("status=500");
    }

    @Test
    void 쿼리_문자열은_로그에_남기지_않는다() {
        // given
        ClientRequest request = request("http://localhost/oauth/token?code=secret-code&x=1");

        // when
        ExternalApiLoggingExchangeFilter.logCalls().filter(request, respondingWith(HttpStatus.OK)).block();

        // then
        assertThat(logs.only().getFormattedMessage())
                .contains("path=/oauth/token")
                .doesNotContain("secret-code")
                .doesNotContain("code=");
    }

    @Test
    void 호출이_실패하면_WARN으로_남기고_스택트레이스도_함께_남긴다() {
        // given
        ClientRequest request = request("http://localhost/oauth/token");
        ExchangeFunction failing = req -> Mono.error(new IllegalStateException("connection reset"));

        // when
        assertThatThrownBy(() -> ExternalApiLoggingExchangeFilter.logCalls().filter(request, failing).block())
                .isInstanceOf(IllegalStateException.class);

        // then
        ILoggingEvent event = logs.only();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .startsWith("event=EXTERNAL_API_CALL_FAILED method=POST path=/oauth/token durationMs=")
                .contains("err=java.lang.IllegalStateException: connection reset");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("connection reset");
    }

    @Test
    void 호출이_실패해도_예외는_호출자에게_그대로_전달된다() {
        // given
        IllegalStateException cause = new IllegalStateException("connection reset");
        ClientRequest request = request("http://localhost/oauth/token");
        ExchangeFunction failing = req -> Mono.error(cause);

        // when, then
        assertThatThrownBy(() -> ExternalApiLoggingExchangeFilter.logCalls().filter(request, failing).block())
                .isSameAs(cause);
    }
}
