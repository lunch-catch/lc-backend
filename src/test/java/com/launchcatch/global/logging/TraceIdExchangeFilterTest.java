package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

class TraceIdExchangeFilterTest {

    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();

    private final ExchangeFunction next = request -> {
        sent.set(request);
        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // 필터를 거쳐 실제로 나가는 요청을 돌려준다.
    private ClientRequest requestSentThroughFilter() {
        ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://localhost/ping")).build();
        TraceIdExchangeFilter.propagateTraceId().filter(request, next).block();
        return sent.get();
    }

    @Test
    void MDC에_traceId가_있으면_X_Trace_Id_헤더로_실어_보낸다() {
        // given
        MDC.put("traceId", "abc123");

        // when
        ClientRequest request = requestSentThroughFilter();

        // then
        assertThat(request.headers().getFirst("X-Trace-Id")).isEqualTo("abc123");
    }

    @Test
    void MDC에_traceId가_없으면_헤더를_붙이지_않는다() {
        // when
        ClientRequest request = requestSentThroughFilter();

        // then
        assertThat(request.headers().getFirst("X-Trace-Id")).isNull();
    }

    @Test
    void 헤더를_붙여도_요청의_메서드와_주소는_그대로다() {
        // given
        MDC.put("traceId", "abc123");

        // when
        ClientRequest request = requestSentThroughFilter();

        // then
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.url()).isEqualTo(URI.create("http://localhost/ping"));
    }
}
