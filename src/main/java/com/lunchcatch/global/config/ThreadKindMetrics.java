package com.lunchcatch.global.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/*
 * 플랫폼 스레드를 이름 끝의 숫자를 지운 종류로 묶어 센다(#1095).
 * 어느 종류가 언제부터 늘었는지를 지난 구간까지 그래프로 본다. 그 순간의 자세한 모습은 스레드 덤프가 보여 준다.
 * 가상 스레드는 JVM 이 알려 주지 않아 세지 않는다.
 */
@Component
public class ThreadKindMetrics implements MeterBinder, AutoCloseable {

    static final String METRIC = "jvm.threads.kind";
    static final String OTHER = "other";
    // 이름이 매번 바뀌는 스레드가 생겨도 시계열이 끝없이 늘지 않게 막는다
    static final int MAX_KINDS = 50;
    private static final long REFRESH_SECONDS = 15;
    private static final Pattern TRAILING_NUMBERS = Pattern.compile("([-_#. ]*\\d+)+$");

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final Set<String> knownKinds = new HashSet<>();
    /*
     * 게이지는 이 값을 읽는다.
     * 다시 세면 값만 바꾸고 게이지는 다시 등록하지 않는다.
     */
    private final Map<String, AtomicInteger> counts = new HashMap<>();
    /*
     * @Scheduled 는 배치 서버에서만 켜져 앱 서버에서는 돌지 않는다.
     * 그래서 서버 역할과 상관없이 도는 데몬 스레드 하나로 센다.
     */
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("thread-kind-metrics").daemon().factory());
    private MultiGauge gauge;

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge = MultiGauge.builder(METRIC)
                .description("이름 끝의 숫자를 지운 종류별 플랫폼 스레드 수")
                .baseUnit("threads")
                .register(registry);
        refresh();
        refresher.scheduleWithFixedDelay(this::refresh, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    /*
     * 이미 있는 종류는 덮어써 다시 등록하지 않는다(#1219).
     * 덮어쓰면 줄마다 지웠다 다시 넣고, 그 사이에 긁힌 수집은 같은 꼬리표가 둘 보여 통째로 실패한다.
     */
    synchronized void refresh() {
        Map<String, Integer> now = count(liveThreadNames());
        counts.keySet().retainAll(now.keySet());
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        now.forEach((kind, n) -> {
            AtomicInteger value = counts.computeIfAbsent(kind, k -> new AtomicInteger());
            value.set(n);
            rows.add(MultiGauge.Row.of(Tags.of("kind", kind), value, AtomicInteger::get));
        });
        // 이번에 없는 종류의 시계열은 지운다
        gauge.register(rows, false);
    }

    synchronized Map<String, Integer> count(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            String kind = kindOf(name);
            if (!knownKinds.contains(kind)) {
                if (knownKinds.size() >= MAX_KINDS) {
                    kind = OTHER;
                } else {
                    knownKinds.add(kind);
                }
            }
            counts.merge(kind, 1, Integer::sum);
        }
        return counts;
    }

    // 중간의 숫자는 남겨 http-nio-8080-exec 와 http-nio-8081-exec 를 가른다
    static String kindOf(String threadName) {
        return TRAILING_NUMBERS.matcher(threadName).replaceFirst("");
    }

    private List<String> liveThreadNames() {
        List<String> names = new ArrayList<>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            // 세는 사이에 끝난 스레드는 null 로 온다
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }

    @Override
    public void close() {
        refresher.shutdownNow();
    }
}
