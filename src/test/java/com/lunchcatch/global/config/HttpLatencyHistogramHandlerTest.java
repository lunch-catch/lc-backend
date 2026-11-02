package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpLatencyHistogramHandlerTest {

    private static final int MAX_URIS = 3;

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final ObservationRegistry observations = ObservationRegistry.create();

    HttpLatencyHistogramHandlerTest() {
        // 앱처럼 기존 요청 시간 지표와 새 히스토그램을 함께 기록한다
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(registry))
                .observationHandler(new HttpLatencyHistogramHandler(registry, MAX_URIS));
    }

    @Test
    @DisplayName("모든 API 의 요청 시간을 uri 라벨 하나로 새 지표의 버킷에 남긴다")
    void 모든_API_를_버킷으로_남긴다() {
        request("/v1/feed", 200);
        request("/v1/owners/me", 401);

        String scrape = registry.scrape();
        assertThat(scrape).contains("http_server_requests_histogram_seconds_count{uri=\"/v1/feed\"} 1");
        assertThat(scrape).contains("http_server_requests_histogram_seconds_count{uri=\"/v1/owners/me\"} 1");
        assertThat(scrape).contains("http_server_requests_histogram_seconds_bucket{uri=\"/v1/feed\",le=");
    }

    @Test
    @DisplayName("기존 요청 시간 지표와 함께 수집해도 수집이 깨지지 않고 기존 지표는 버킷 없이 상태값을 남긴다")
    void 기존_지표와_함께_수집된다() {
        request("/v1/feed", 200);
        request("/v1/feed", 500);

        String scrape = registry.scrape();
        assertThat(scrape).contains("http_server_requests_seconds_count{");
        assertThat(scrape).contains("status=\"500\"");
        assertThat(scrape).doesNotContain("http_server_requests_seconds_bucket");
        assertThat(scrape).contains("http_server_requests_histogram_seconds_count{uri=\"/v1/feed\"} 2");
    }

    @Test
    @DisplayName("API 종류가 상한을 넘으면 그 뒤에 처음 보는 API 는 새 지표에 남기지 않는다")
    void 상한을_넘으면_남기지_않는다() {
        for (int i = 0; i < MAX_URIS; i++) {
            request("/v1/api-" + i, 200);
        }
        request("/v1/over", 200);
        request("/v1/api-0", 200);

        String scrape = registry.scrape();
        assertThat(scrape).doesNotContain("http_server_requests_histogram_seconds_count{uri=\"/v1/over\"}");
        assertThat(scrape).contains("http_server_requests_histogram_seconds_count{uri=\"/v1/api-0\"} 2");
    }

    @Test
    @DisplayName("버킷은 k6 의 시간 초과인 60초까지 있다")
    void 버킷은_60초까지_있다() {
        request("/v1/impressions", 202);

        Matcher le = Pattern.compile("histogram_seconds_bucket\\{[^}]*le=\"([0-9.]+)\"").matcher(registry.scrape());
        double largest = 0;
        while (le.find()) {
            largest = Math.max(largest, Double.parseDouble(le.group(1)));
        }
        assertThat(largest).isGreaterThanOrEqualTo(60.0);
    }

    private void request(String pathPattern, int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(new MockHttpServletRequest("GET", pathPattern), response);
        context.setPathPattern(pathPattern);
        Observation.createNotStarted(new DefaultServerRequestObservationConvention(), () -> context, observations)
                .start()
                .stop();
    }
}
