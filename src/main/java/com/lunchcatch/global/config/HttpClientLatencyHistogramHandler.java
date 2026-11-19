package com.lunchcatch.global.config;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;

/*
 * 바깥 서비스를 부르는 호출의 시간을 상대(client.name)와 경로(uri)만 단 히스토그램으로 한 번 더 남긴다(#1166).
 * 카카오와 토스의 WebClient 는 Boot 가 관측을 붙인 WebClient.Builder 로 만들어 이 핸들러를 지난다.
 * 들어오는 요청의 HttpLatencyHistogramHandler 와 같은 이유로 기존 http.client.requests 에 버킷을 켜지 않는다.
 * 상태값, 메서드, 예외의 조합마다 버킷이 붙어 시계열이 불어나기 때문이다. 호출 수는 기존 지표가 갖는다.
 *
 * 실패 수는 상대만 단 카운터로 따로 센다(#1274).
 * 기존 지표의 실패 시계열은 첫 실패 때 1 로 생겨 increase() 가 그 첫 한 건을 세지 않는다.
 * 상대마다 0 인 시점을 두려고 기동 때 registerClient 로, 등록하지 않은 상대는 첫 호출 때 만든다.
 * 5xx(SERVER_ERROR)와 응답 없음(UNKNOWN)만 센다. 4xx 는 카드 거절 같은 업무상 거절에도 와서 세지 않는다.
 */
@Component
public class HttpClientLatencyHistogramHandler implements ObservationHandler<ClientRequestObservationContext> {

    static final String METRIC = "http.client.requests.histogram";
    static final String FAILURES = "http.client.failures";
    private static final Set<String> FAILED_OUTCOMES = Set.of("SERVER_ERROR", "UNKNOWN");
    // 상대와 경로의 조합이 늘어도 시계열이 이 수 x 버킷 수를 넘지 않게 막는다
    static final int MAX_CALLS = 100;
    // 외부 호출의 응답 시간 제한(WebClientConfig 5초, 토스는 설정)보다 넉넉히 둬 느린 호출이 마지막 버킷에 뭉치지 않게 한다
    private static final Duration MAX_EXPECTED = Duration.ofSeconds(30);

    private final MeterRegistry registry;
    private final int maxCalls;
    private final Set<String> knownCalls = ConcurrentHashMap.newKeySet();

    @Autowired
    public HttpClientLatencyHistogramHandler(MeterRegistry registry) {
        this(registry, MAX_CALLS);
    }

    HttpClientLatencyHistogramHandler(MeterRegistry registry, int maxCalls) {
        this.registry = registry;
        this.maxCalls = maxCalls;
    }

    // 상대의 실패 수를 0 으로 만든다. 기동 때 부르면 배포 뒤 첫 호출의 실패도 센다
    public void registerClient(String clientName) {
        failures(clientName);
    }

    @Override
    public void onStart(ClientRequestObservationContext context) {
        context.put(Timer.Sample.class, Timer.start(registry));
    }

    @Override
    public void onStop(ClientRequestObservationContext context) {
        KeyValue clientName = context.getLowCardinalityKeyValue("client.name");
        KeyValue uri = context.getLowCardinalityKeyValue("uri");
        if (clientName != null) {
            countFailure(clientName.getValue(), context.getLowCardinalityKeyValue("outcome"));
        }
        // 관측 규약을 바꿔 꼬리표가 빠지면 그 호출은 남기지 않는다
        if (clientName == null || uri == null || !admit(clientName.getValue() + " " + uri.getValue())) {
            return;
        }
        Timer.Sample sample = context.getRequired(Timer.Sample.class);
        sample.stop(Timer.builder(METRIC)
                .description("외부 호출의 상대와 경로별 시간. p99 를 계산하는 버킷을 내보낸다")
                .tag("client.name", clientName.getValue())
                .tag("uri", uri.getValue())
                .publishPercentileHistogram()
                .maximumExpectedValue(MAX_EXPECTED)
                .register(registry));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ClientRequestObservationContext;
    }

    // 실패 수는 상대마다 하나라 경로 상한과 상관없이 센다
    private void countFailure(String clientName, KeyValue outcome) {
        Counter failures = failures(clientName);
        if (outcome != null && FAILED_OUTCOMES.contains(outcome.getValue())) {
            failures.increment();
        }
    }

    private Counter failures(String clientName) {
        return Counter.builder(FAILURES)
                .description("외부 호출 중 5xx 와 응답 없음으로 끝난 수. 상대마다 0 부터 센다")
                .tag("client.name", clientName)
                .register(registry);
    }

    // 상한을 넘은 뒤에 처음 보는 호출은 받지 않는다. 이미 받은 호출은 계속 남긴다
    private boolean admit(String call) {
        if (knownCalls.contains(call)) {
            return true;
        }
        synchronized (knownCalls) {
            if (knownCalls.size() >= maxCalls) {
                return false;
            }
            knownCalls.add(call);
            return true;
        }
    }
}
