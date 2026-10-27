package com.lunchcatch.billing.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 승인 결과를 모르는 결제의 재조회 설정. 키는 {@code billing.payment.recheck.*} 이다.
 *
 * 값은 측정 전 예시값이라 코드에 상수로 두지 않는다. 기본값으로 첫 재조회는 1분 뒤이고 이후 간격은
 * 2, 5, 10, 30분이다. 5회째까지 확정하지 못하면 포기하므로 총 재조회 기간은 약 48분이다.
 *
 * @param maxRetry    재조회 상한. 시도 횟수가 이 값에 닿으면 FAILED_PERMANENT 로 두고 관리자가 확정한다
 * @param firstDelay  UNKNOWN 이 된 뒤 첫 재조회까지의 간격
 * @param backoff     확정하지 못한 시도 뒤 다음 재조회까지의 간격. n번째 시도 뒤에는 n번째 값을 쓰고 목록이 끝나면 마지막 값을 유지한다
 * @param jitterRatio 간격에 더하고 빼는 무작위 폭의 비율. 0.1 이면 간격의 90%에서 110% 사이다
 * @param batchSize   워커가 한 번에 선점하는 행의 상한
 * @param claimLease  선점한 행을 다른 서버가 집지 않게 미루는 시간. 한 번에 선점한 행을 모두 처리하는 시간보다 길어야 한다
 */
@Validated
@ConfigurationProperties(prefix = "billing.payment.recheck")
public record PaymentRecheckProperties(
        @PositiveOrZero @DefaultValue("5") int maxRetry,
        @NotNull @DefaultValue("1m") Duration firstDelay,
        @NotEmpty @DefaultValue({"2m", "5m", "10m", "30m"}) List<Duration> backoff,
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.1") double jitterRatio,
        @Positive @DefaultValue("20") int batchSize,
        @NotNull @DefaultValue("5m") Duration claimLease
) {

    /**
     * 확정하지 못한 시도 뒤 다음 재조회까지의 간격을 계산한다.
     *
     * @param attemptCount 지금까지 시도한 횟수. 1 이상이다
     * @param unitRandom   0 이상 1 미만의 무작위 값. 0 이면 간격의 최솟값, 1 에 가까우면 최댓값이다
     */
    public Duration delayAfter(int attemptCount, double unitRandom) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("시도 횟수는 1 이상이어야 합니다: " + attemptCount);
        }
        Duration base = backoff.get(Math.min(attemptCount, backoff.size()) - 1);
        double factor = 1 + jitterRatio * (2 * unitRandom - 1);
        return Duration.ofMillis(Math.round(base.toMillis() * factor));
    }
}
