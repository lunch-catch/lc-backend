package com.lunchcatch.analytics.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 부정 경고의 임계값. 키는 {@code analytics.fraud.*} 이다. 범위를 벗어나면 기동이 실패한다.
 *
 * @param topMembers          집중 경고가 점유율을 재는 상위 회원 수
 * @param concentrationShare  상위 회원의 유효 노출 점유율이 이 값을 넘으면 집중이다. 0 초과 1 이하다
 * @param minimumImpressions  집중 경고를 판정하는 하루 유효 노출의 최솟값. 이보다 적으면 판정하지 않는다
 * @param minimumInvalidCount 무효 비율 경고를 띄우는 무효 노출의 최솟값. 이보다 적으면 비율이 높아도 경고하지 않는다
 */
@Validated
@ConfigurationProperties(prefix = "analytics.fraud")
public record AnalyticsFraudProperties(
        @Positive @Max(100) @DefaultValue("5") int topMembers,
        @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0") @DefaultValue("0.5") double concentrationShare,
        @Positive @DefaultValue("100") long minimumImpressions,
        @Positive @DefaultValue("100") long minimumInvalidCount
) {
}
