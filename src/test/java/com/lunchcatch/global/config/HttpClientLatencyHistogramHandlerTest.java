package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.boot.webclient.autoconfigure.WebClientObservationAutoConfiguration;
import org.springframework.web.reactive.function.client.WebClient;

class HttpClientLatencyHistogramHandlerTest {

    private static final int MAX_CALLS = 2;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            int status = exchange.getRequestURI().getPath().startsWith("/fail") ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("나가는 호출의 시간을 상대와 경로 꼬리표만 단 새 지표의 버킷에 남긴다")
    void 나가는_호출을_상대와_경로로_버킷에_남긴다() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        WebClient client = WebClient.builder().observationRegistry(observations(registry, MAX_CALLS)).build();

        call(client, "/v1/user/logout");
        call(client, "/fail/v1/payments/confirm");

        String scrape = registry.scrape();
        assertThat(scrape).contains("http_client_requests_histogram_seconds_count{client_name=\"localhost\",uri=\"/v1/user/logout\"} 1");
        assertThat(scrape).contains("http_client_requests_histogram_seconds_count{client_name=\"localhost\",uri=\"/fail/v1/payments/confirm\"} 1");
        assertThat(scrape).contains("http_client_requests_histogram_seconds_bucket{client_name=\"localhost\",uri=\"/v1/user/logout\",le=");
        // 호출 수와 실패는 Boot 의 기존 지표가 상태와 결과 꼬리표로 그대로 갖는다
        assertThat(scrape).contains("outcome=\"SERVER_ERROR\"");
    }

    // 경로가 늘어도 시계열이 상한 x 버킷 수를 넘지 않는다
    @Test
    @DisplayName("상한을 넘은 뒤 처음 보는 호출은 새 지표에 남기지 않는다")
    void 상한을_넘은_호출은_남기지_않는다() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        WebClient client = WebClient.builder().observationRegistry(observations(registry, MAX_CALLS)).build();

        call(client, "/a");
        call(client, "/b");
        call(client, "/c");
        call(client, "/a");

        String scrape = registry.scrape();
        assertThat(scrape).contains("http_client_requests_histogram_seconds_count{client_name=\"localhost\",uri=\"/a\"} 2");
        assertThat(scrape).contains("uri=\"/b\"");
        assertThat(scrape).doesNotContain("http_client_requests_histogram_seconds_count{client_name=\"localhost\",uri=\"/c\"}");
    }

    // 카카오와 토스의 WebClient 는 Boot 가 만든 builder 로 만든다. 그 builder 가 관측을 붙이는지 본다
    @Test
    @DisplayName("Boot 가 만든 WebClient.Builder 로 만든 클라이언트는 기존 지표와 새 지표를 모두 남긴다")
    void Boot_의_builder_로_만든_클라이언트가_지표를_남긴다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                        MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class, WebClientAutoConfiguration.class,
                        WebClientObservationAutoConfiguration.class))
                .withUserConfiguration(WebClientConfig.class, HttpClientLatencyHistogramHandler.class)
                .run(context -> {
                    WebClient client = context.getBean(WebClient.Builder.class).build();

                    call(client, "/oauth/token");

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.get("http.client.requests").tag("uri", "/oauth/token").timer().count()).isEqualTo(1);
                    assertThat(registry.get(HttpClientLatencyHistogramHandler.METRIC)
                            .tag("client.name", "localhost").tag("uri", "/oauth/token").timer().count()).isEqualTo(1);
                });
    }

    private static ObservationRegistry observations(MeterRegistry registry, int maxCalls) {
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(registry))
                .observationHandler(new HttpClientLatencyHistogramHandler(registry, maxCalls));
        return observations;
    }

    private void call(WebClient client, String path) {
        client.get().uri("http://localhost:" + port + path).retrieve().toBodilessEntity()
                .onErrorResume(error -> reactor.core.publisher.Mono.empty()).block();
    }
}
