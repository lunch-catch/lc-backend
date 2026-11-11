package com.lunchcatch.adserving.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서빙 기록 버퍼({@code ServeLogBuffer})의 조정 값. 키는 {@code adserving.serve-log.buffer.*} 이다.
 *
 * 값을 고른 이유는 {@code application.yml} 의 같은 자리가 갖는다.
 *
 * @param capacity      대기열에 담을 수 있는 피드 수
 * @param feedsPerBatch 한 번에 저장하는 피드 수의 상한
 */
@ConfigurationProperties(prefix = "adserving.serve-log.buffer")
public record ServeLogBufferProperties(
        @DefaultValue("5000") int capacity,
        @DefaultValue("50") int feedsPerBatch
) {

    public ServeLogBufferProperties {
        TuningChecks.positive("capacity", capacity);
        TuningChecks.positive("feeds-per-batch", feedsPerBatch);
        if (feedsPerBatch > capacity) {
            throw new IllegalArgumentException("feeds-per-batch 는 capacity 보다 클 수 없다");
        }
    }
}
