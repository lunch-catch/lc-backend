package com.launchcatch.billing.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 승인 결과를 모르는 결제의 재조회 설정. 키는 {@code billing.payment.recheck.*} 이다.
 *
 * 값은 구현 계획 D-10 의 시작값이고 측정 전 예시값이라 코드에 상수로 두지 않는다.
 * 결제를 UNKNOWN 으로 저장할 때 아웃박스 행에 상한과 첫 재조회 시각을 이 값으로 넣는다.
 *
 * @param maxRetry   재조회 상한. 넘으면 FAILED_PERMANENT 로 두고 관리자가 확정한다
 * @param firstDelay UNKNOWN 이 된 뒤 첫 재조회까지의 간격
 */
@Validated
@ConfigurationProperties(prefix = "billing.payment.recheck")
public record PaymentRecheckProperties(
        @PositiveOrZero @DefaultValue("10") int maxRetry,
        @NotNull @DefaultValue("1m") Duration firstDelay
) {
}
