package com.lunchcatch.global.config;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기준 초과를 가르는 기준 값이다. 키는 {@code lunchcatch.observability.targets.*} 이다.
 *
 * 값의 원본은 도메인 설계서와 품질 문서이고 이 설정은 그 사본이다. 원본을 바꾸면 이 설정도 같은 PR 에서 고친다.
 * 품질 문서의 {@code [인프라]} 항목 값은 이 설정에 두지 않고 lc-infra 의 규칙 파일이 갖는다.
 *
 * @param defaults 종류마다의 기본값. 이름별 값이 없는 것은 이 값으로 판정한다
 * @param api      API 마다의 p99 목표. 키는 {@code 메서드 경로} 다
 * @param relay    아웃박스 릴레이마다 가장 오래 기다린 미발행 건의 대기 시간
 * @param worker   비동기 워커마다 가장 오래 기다린 일의 대기 시간
 * @param job      cron 작업마다 실행 한 번이 끝나야 하는 시간. 키는 작업 타이머의 {@code scheduler} 꼬리표다
 * @param global   이름마다 다르지 않은 앱 전체의 한도
 */
@ConfigurationProperties(prefix = OverrunThresholdProperties.PREFIX)
public record OverrunThresholdProperties(
        Map<String, Duration> defaults,
        Map<String, Duration> api,
        Map<String, Duration> relay,
        Map<String, Duration> worker,
        Map<String, Duration> job,
        Map<String, Duration> global
) {

    static final String PREFIX = "lunchcatch.observability.targets";
    // 앱 전체의 한도는 키마다 값을 적어 기본값을 두지 않는다
    static final Set<String> KINDS_WITH_DEFAULT = Set.of("api", "relay", "worker", "job");

    public OverrunThresholdProperties {
        defaults = checked("defaults", defaults);
        api = checked("api", api);
        relay = checked("relay", relay);
        worker = checked("worker", worker);
        job = checked("job", job);
        global = checked("global", global);
        for (String kind : defaults.keySet()) {
            if (!KINDS_WITH_DEFAULT.contains(kind)) {
                throw new IllegalArgumentException("기본값을 둘 수 있는 종류는 " + KINDS_WITH_DEFAULT + " 다: " + kind);
            }
        }
    }

    // 종류의 이름과 기준을 함께 돌려준다. 게이지를 등록할 때 순서대로 돈다
    Map<String, Map<String, Duration>> byKind() {
        return Map.of("api", api, "relay", relay, "worker", worker, "job", job, "global", global);
    }

    private static Map<String, Duration> checked(String kind, Map<String, Duration> values) {
        if (values == null) {
            return Map.of();
        }
        values.forEach((name, value) -> {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(kind + "." + name + " 의 기준은 0 보다 커야 한다: " + value);
            }
        });
        return Map.copyOf(values);
    }
}
