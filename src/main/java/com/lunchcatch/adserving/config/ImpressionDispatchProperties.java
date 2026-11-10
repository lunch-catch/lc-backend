package com.lunchcatch.adserving.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 노출 릴레이({@code ImpressionDispatchJob}, {@code ImpressionDispatchService})의 조정 값.
 * 키는 {@code adserving.impression.dispatch.*} 이다.
 *
 * 값을 고른 이유는 {@code application.yml} 의 같은 자리가 갖는다.
 *
 * @param interval    앞 실행이 끝난 뒤 다음 실행까지의 간격. {@code @Scheduled} 가 같은 키를 읽는다
 * @param maxAttempts 한 노출을 넘기려고 시도하는 횟수
 * @param leaseTtl    배치 서버 하나가 릴레이를 맡는 임대의 시간
 */
@ConfigurationProperties(prefix = "adserving.impression.dispatch")
public record ImpressionDispatchProperties(
        @DefaultValue("PT1S") Duration interval,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("30s") Duration leaseTtl
) {

    public ImpressionDispatchProperties {
        TuningChecks.positive("interval", interval);
        TuningChecks.positive("max-attempts", maxAttempts);
        TuningChecks.positive("lease-ttl", leaseTtl);
        // 임대가 주기보다 짧으면 실행 사이에 임대가 풀려 다른 배치 서버가 가져간다
        if (leaseTtl.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("lease-ttl 은 interval 보다 길어야 한다");
        }
    }
}
