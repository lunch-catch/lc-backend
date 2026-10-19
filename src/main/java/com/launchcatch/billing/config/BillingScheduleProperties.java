package com.launchcatch.billing.config;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 시간대별 가중치 설정. 키는 {@code billing.schedule.*} 이다.
 *
 * 광고 서빙은 하루 종일이고 0시부터 23시까지 정시 단위 시간대 24개로 나뉜다. 가중치는 그 시간대마다 하나씩이고
 * 순서는 시간 순이다. 합이 100 일 필요는 없고 비율로만 쓴다. 비율로 나눈 뒤 남는 금액은 마지막으로 가중치가 있는
 * 시간대에 더해 시간대 목표의 합이 그날 예약액과 같다.
 *
 * @param hourlyWeights 0시부터 23시까지의 가중치 24개
 */
@ConfigurationProperties(prefix = "billing.schedule")
public record BillingScheduleProperties(
        @DefaultValue({"2", "1", "1", "1", "1", "1", "1", "3", "4", "5", "8", "10",
                "9", "7", "4", "4", "4", "5", "7", "8", "6", "4", "2", "2"}) List<Integer> hourlyWeights
) {

    /** 하루의 시간대 수다. */
    public static final int HOURS = 24;

    public BillingScheduleProperties {
        Objects.requireNonNull(hourlyWeights, "hourly-weights 가 필요하다");
        hourlyWeights = List.copyOf(hourlyWeights);
        if (hourlyWeights.size() != HOURS) {
            throw new IllegalArgumentException("hourly-weights 개수는 하루 시간대 수 " + HOURS + " 와 같아야 한다");
        }
        if (hourlyWeights.stream().anyMatch(weight -> weight < 0)) {
            throw new IllegalArgumentException("hourly-weights 는 0 이상이어야 한다");
        }
        if (hourlyWeights.stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new IllegalArgumentException("hourly-weights 의 합은 0 보다 커야 한다");
        }
    }
}
