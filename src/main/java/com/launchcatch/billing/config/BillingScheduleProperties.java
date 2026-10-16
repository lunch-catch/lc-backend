package com.launchcatch.billing.config;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 광고 서빙 시각과 시간대별 가중치 설정. 키는 {@code billing.schedule.*} 이다.
 *
 * 서빙은 시작 시각이 속한 시간부터 종료 시각이 속한 시간까지의 정시 단위 시간대로 나뉜다.
 * 가중치는 그 시간대마다 하나씩이고 순서는 시간 순이다. 합이 100 일 필요는 없고 비율로만 쓴다.
 * 시각은 {@code HH:mm} 문자열이다. YAML 이 따옴표 없는 10:00 을 숫자로 읽기 때문에 따옴표로 감싼다.
 *
 * @param servingStart       서빙 시작 시각
 * @param servingEnd         서빙 종료 시각
 * @param impressionValidFor 서빙 뒤 노출 차감이 유효한 시간
 * @param hourlyWeights      시간대별 가중치. 서빙 시간대 수와 같은 개수
 */
@ConfigurationProperties(prefix = "billing.schedule")
public record BillingScheduleProperties(
        @DefaultValue("10:00") String servingStart,
        @DefaultValue("12:59") String servingEnd,
        @DefaultValue("30m") Duration impressionValidFor,
        @DefaultValue({"34", "33", "33"}) List<Integer> hourlyWeights
) {

    public BillingScheduleProperties {
        Objects.requireNonNull(impressionValidFor, "impression-valid-for 가 필요하다");
        Objects.requireNonNull(hourlyWeights, "hourly-weights 가 필요하다");
        LocalTime start = parse(servingStart, "serving-start");
        LocalTime end = parse(servingEnd, "serving-end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("serving-start 는 serving-end 보다 앞서야 한다");
        }
        if (impressionValidFor.isZero() || impressionValidFor.isNegative()) {
            throw new IllegalArgumentException("impression-valid-for 는 0 보다 커야 한다");
        }
        hourlyWeights = List.copyOf(hourlyWeights);
        int hours = end.getHour() - start.getHour() + 1;
        if (hourlyWeights.size() != hours) {
            throw new IllegalArgumentException("hourly-weights 개수는 서빙 시간대 수 " + hours + " 와 같아야 한다");
        }
        if (hourlyWeights.stream().anyMatch(weight -> weight < 0)) {
            throw new IllegalArgumentException("hourly-weights 는 0 이상이어야 한다");
        }
        if (hourlyWeights.stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new IllegalArgumentException("hourly-weights 의 합은 0 보다 커야 한다");
        }
    }

    public LocalTime startTime() {
        return LocalTime.parse(servingStart);
    }

    public LocalTime endTime() {
        return LocalTime.parse(servingEnd);
    }

    // 서빙이 끝난 뒤 진행률을 예약액 전체로 고정하는 시각. 종료 시각의 다음 분이다
    public LocalTime fixedFrom() {
        return endTime().plusMinutes(1);
    }

    // 13:30 해제·대사를 시작하는 시각이다. 고정 시각에서 노출 인정 시간이 더 지나야 그 뒤의 노출이 끝났다고 본다
    public LocalTime releaseTime() {
        return fixedFrom().plus(impressionValidFor);
    }

    private static LocalTime parse(String value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + " 가 필요하다");
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(key + " 는 HH:mm 형식이어야 한다: " + value, e);
        }
    }
}
