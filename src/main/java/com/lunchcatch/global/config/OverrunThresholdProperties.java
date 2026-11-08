package com.lunchcatch.global.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기준 초과를 가르는 기준이다. 키는 {@code lunchcatch.observability.targets.*} 이다.
 *
 * 값의 원본은 도메인 설계서와 품질 문서이고 이 설정은 그 사본이다. 원본을 바꾸면 이 설정도 같은 PR 에서 고친다.
 * 품질 문서의 {@code [인프라]} 항목 값(GC 일시정지, 커넥션 획득 대기)은 lc-infra 의 규칙 파일이 갖는다.
 * 종류마다 기본값이 있어야 한다. 이름별 값이 없는 것은 기본값으로 판정한다.
 *
 * @param apiDefault    목표가 없는 API 를 판정할 값
 * @param api           API 마다의 p99 목표. 키는 {@code 메서드 경로} 다
 * @param relayDefault  이름별 값이 없는 아웃박스 릴레이의 가장 오래 기다린 미발행 건의 대기 시간
 * @param relay         릴레이마다의 대기 시간. 키는 공통 지표의 {@code relay} 꼬리표다
 * @param workerDefault 이름별 값이 없는 비동기 워커의 가장 오래 기다린 일의 대기 시간
 * @param worker        워커마다의 대기 시간. 키는 공통 지표의 {@code worker} 꼬리표다
 * @param jobDefault    마감이 없는 cron 작업이 실행 한 번에 끝나야 하는 시간
 * @param job           cron 작업마다의 마감을 시작에서 잰 시간. 키는 작업 타이머의 {@code scheduler} 꼬리표다
 */
@ConfigurationProperties(prefix = OverrunThresholdProperties.PREFIX)
public record OverrunThresholdProperties(
        Duration apiDefault, Map<String, Duration> api,
        Duration relayDefault, Map<String, Duration> relay,
        Duration workerDefault, Map<String, Duration> worker,
        Duration jobDefault, Map<String, Duration> job
) {

    static final String PREFIX = "lunchcatch.observability.targets";
    // 기본값의 게이지가 쓰는 이름이라 이름별 키로 쓰지 못한다
    static final String DEFAULT_NAME = "default";

    public OverrunThresholdProperties {
        // 대시보드와 판정이 이름별 값이 없는 것을 기본값으로 견주므로 빠지면 뜰 때 막는다
        checkPositive("api-default", apiDefault);
        checkPositive("relay-default", relayDefault);
        checkPositive("worker-default", workerDefault);
        checkPositive("job-default", jobDefault);
        api = named("api", api);
        relay = named("relay", relay);
        worker = named("worker", worker);
        job = named("job", job);
    }

    // 종류마다 기본값과 이름별 값을 함께 돌려준다. 기본값의 이름은 default 다
    Map<String, Map<String, Duration>> byKind() {
        Map<String, Map<String, Duration>> kinds = new LinkedHashMap<>();
        kinds.put("api", withDefault(apiDefault, api));
        kinds.put("relay", withDefault(relayDefault, relay));
        kinds.put("worker", withDefault(workerDefault, worker));
        kinds.put("job", withDefault(jobDefault, job));
        return kinds;
    }

    private static Map<String, Duration> withDefault(Duration fallback, Map<String, Duration> named) {
        Map<String, Duration> all = new LinkedHashMap<>(named);
        all.put(DEFAULT_NAME, fallback);
        return all;
    }

    private static Map<String, Duration> named(String kind, Map<String, Duration> values) {
        Map<String, Duration> copy = values == null ? Map.of() : Map.copyOf(values);
        if (copy.containsKey(DEFAULT_NAME)) {
            throw new IllegalArgumentException(kind + " 의 키로 " + DEFAULT_NAME + " 를 쓰지 못한다. 기본값은 "
                    + kind + "-default 에 적는다");
        }
        copy.forEach((name, value) -> checkPositive(kind + "." + name, value));
        return copy;
    }

    private static void checkPositive(String key, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " 의 기준은 0 보다 커야 한다: " + value);
        }
    }
}
