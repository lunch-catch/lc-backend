package com.lunchcatch.global.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/*
 * 모든 API 의 요청 시간을 uri 라벨 하나만 단 히스토그램 지표로 한 번 더 남긴다(#1092).
 * 기존 http.server.requests 에 버킷을 켜면 상태값, 메서드, 예외의 조합마다 버킷이 70개 남짓 붙어 운영 Prometheus 의 시계열이 수만 개로 는다.
 * 같은 이름에서 일부만 켜면 지표 종류가 섞여 수집 전체가 실패하므로 이름을 따로 둔다.
 * 상태값별 요청 수와 오류율은 기존 지표가 그대로 갖는다.
 */
@Component
public class HttpLatencyHistogramHandler implements ObservationHandler<ServerRequestObservationContext> {

    static final String METRIC = "http.server.requests.histogram";
    // 엔드포인트가 늘어도 시계열이 이 수 x 버킷 수를 넘지 않게 막는다
    static final int MAX_URIS = 200;
    // k6 의 시간 초과가 60초라 그보다 느린 요청이 마지막 버킷에 뭉치지 않게 한다
    private static final Duration MAX_EXPECTED = Duration.ofSeconds(60);

    private final MeterRegistry registry;
    private final int maxUris;
    private final Set<String> knownUris = ConcurrentHashMap.newKeySet();

    @Autowired
    public HttpLatencyHistogramHandler(MeterRegistry registry) {
        this(registry, MAX_URIS);
    }

    HttpLatencyHistogramHandler(MeterRegistry registry, int maxUris) {
        this.registry = registry;
        this.maxUris = maxUris;
    }

    @Override
    public void onStart(ServerRequestObservationContext context) {
        context.put(Timer.Sample.class, Timer.start(registry));
    }

    @Override
    public void onStop(ServerRequestObservationContext context) {
        String uri = context.getLowCardinalityKeyValue("uri").getValue();
        if (!admit(uri)) {
            return;
        }
        Timer.Sample sample = context.getRequired(Timer.Sample.class);
        sample.stop(Timer.builder(METRIC)
                .description("API 별 요청 시간. p99 를 계산하는 버킷을 내보낸다")
                .tag("uri", uri)
                .publishPercentileHistogram()
                .maximumExpectedValue(MAX_EXPECTED)
                .register(registry));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext;
    }

    // 상한을 넘은 뒤에 처음 보는 API 는 받지 않는다. 이미 받은 API 는 계속 남긴다
    private boolean admit(String uri) {
        if (knownUris.contains(uri)) {
            return true;
        }
        synchronized (knownUris) {
            if (knownUris.size() >= maxUris) {
                return false;
            }
            knownUris.add(uri);
            return true;
        }
    }
}
