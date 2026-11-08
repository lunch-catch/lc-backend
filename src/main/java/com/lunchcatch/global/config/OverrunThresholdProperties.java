package com.lunchcatch.global.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API 마다의 p99 목표다. 키는 {@code lunchcatch.observability.targets.*} 이다.
 *
 * 값의 원본은 그 도메인의 API 설계서이고 이 설정은 그 사본이다. 원본을 바꾸면 이 설정도 같은 PR 에서 고친다.
 * 릴레이, 워커, cron 의 기준은 알리는 문턱이라 lc-infra 의 규칙 파일이 갖는다.
 *
 * @param apiDefault 목표가 없는 API 를 판정할 값
 * @param api        API 마다의 목표. 키는 {@code 메서드 경로} 다
 */
@ConfigurationProperties(prefix = OverrunThresholdProperties.PREFIX)
public record OverrunThresholdProperties(Duration apiDefault, Map<String, Duration> api) {

    static final String PREFIX = "lunchcatch.observability.targets";

    public OverrunThresholdProperties {
        if (apiDefault != null) {
            checkPositive("api-default", apiDefault);
        }
        api = api == null ? Map.of() : Map.copyOf(api);
        api.forEach((name, value) -> checkPositive("api." + name, value));
    }

    private static void checkPositive(String key, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(key + " 의 기준은 0 보다 커야 한다: " + value);
        }
    }
}
