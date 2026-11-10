package com.lunchcatch.billing.reservation.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 충전 직후 재예약을 다시 시도하는 설정. 키는 {@code billing.recharge-reservation.*} 이다.
 *
 * 값은 측정 전 예시값이라 코드에 상수로 두지 않는다. 기본값으로 실패한 재예약은 1, 2, 5, 10분 뒤에 다시 하고
 * 그 뒤로는 10분마다 한다. 횟수 상한은 없고 충전한 영업일이 지나면 닫는다.
 *
 * @param backoff    실패한 시도 뒤 다음 재예약까지의 간격. n번째 실패 뒤에는 n번째 값을 쓰고 목록이 끝나면 마지막 값을 유지한다
 * @param batchSize  워커가 한 번에 선점하는 행의 상한. 선점한 행이 이만큼이면 같은 실행에서 다시 선점한다
 * @param claimLease 선점한 행을 다른 서버가 집지 않게 미루는 시간. 한 번에 선점한 행을 모두 처리하는 시간보다 길어야 한다
 * @param maxRunTime 한 번 실행에서 선점을 되풀이하는 시간의 상한. 이 시간이 지나면 남은 행은 다음 실행이 처리한다
 */
@Validated
@ConfigurationProperties(prefix = "billing.recharge-reservation")
public record RechargeReservationRetryProperties(
        @NotEmpty @DefaultValue({"1m", "2m", "5m", "10m"}) List<Duration> backoff,
        @Positive @DefaultValue("20") int batchSize,
        @NotNull @DefaultValue("5m") Duration claimLease,
        @NotNull @DefaultValue("50s") Duration maxRunTime
) {

    /**
     * 실패한 시도 뒤 다음 재예약까지의 간격을 돌려준다.
     *
     * @param attemptCount 지금까지 실패한 횟수. 1 이상이다
     */
    public Duration delayAfter(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("실패 횟수는 1 이상이어야 합니다: " + attemptCount);
        }
        return backoff.get(Math.min(attemptCount, backoff.size()) - 1);
    }
}
