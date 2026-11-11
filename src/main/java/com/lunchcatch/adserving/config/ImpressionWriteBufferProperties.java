package com.lunchcatch.adserving.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 노출 저장 버퍼({@code ImpressionWriteBuffer})의 조정 값. 키는 {@code adserving.impression.write-buffer.*} 이다.
 *
 * 값을 고른 이유와 부하 시험에서 바꿔 볼 때 볼 것은 {@code application.yml} 의 같은 자리가 갖는다.
 *
 * @param capacity     대기열에 담을 수 있는 노출 수
 * @param batchSize    한 번에 저장하는 노출 수의 상한
 * @param maxWait      묶음을 채우려고 기다리는 시간의 상한
 * @param offerTimeout 대기열이 찼을 때 넣으려고 기다리는 시간
 * @param firstBackoff 저장에 실패한 뒤 첫 재시도까지의 간격
 * @param maxBackoff   재시도 간격의 상한
 * @param maxAttempts  한 묶음을 저장하려고 시도하는 횟수
 */
@ConfigurationProperties(prefix = "adserving.impression.write-buffer")
public record ImpressionWriteBufferProperties(
        @DefaultValue("10000") int capacity,
        @DefaultValue("500") int batchSize,
        @DefaultValue("200ms") Duration maxWait,
        @DefaultValue("100ms") Duration offerTimeout,
        @DefaultValue("200ms") Duration firstBackoff,
        @DefaultValue("5s") Duration maxBackoff,
        @DefaultValue("10") int maxAttempts
) {

    public ImpressionWriteBufferProperties {
        TuningChecks.positive("capacity", capacity);
        TuningChecks.positive("batch-size", batchSize);
        TuningChecks.positive("max-wait", maxWait);
        TuningChecks.positive("offer-timeout", offerTimeout);
        TuningChecks.positive("first-backoff", firstBackoff);
        TuningChecks.positive("max-backoff", maxBackoff);
        TuningChecks.positive("max-attempts", maxAttempts);
        if (batchSize > capacity) {
            throw new IllegalArgumentException("batch-size 는 capacity 보다 클 수 없다");
        }
        if (firstBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("first-backoff 는 max-backoff 보다 길 수 없다");
        }
    }
}
