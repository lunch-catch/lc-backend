package com.launchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PaymentRecheckPropertiesTest {

    private static final String PREFIX = "billing.payment.recheck";

    private static PaymentRecheckProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate(PREFIX, PaymentRecheckProperties.class);
    }

    @Test
    @DisplayName("아무 값도 주지 않으면 5회, 첫 재조회 1분, 간격 2·5·10·30분으로 채워진다")
    void 기본값() {
        PaymentRecheckProperties properties = bind(Map.of());

        assertThat(properties.maxRetry()).isEqualTo(5);
        assertThat(properties.firstDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.backoff()).containsExactly(
                Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(30));
        assertThat(properties.jitterRatio()).isEqualTo(0.1);
        assertThat(properties.batchSize()).isEqualTo(20);
        assertThat(properties.claimLease()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("설정으로 상한과 간격 목록을 바꾼다")
    void 설정으로_바꾼다() {
        PaymentRecheckProperties properties = bind(Map.of(
                PREFIX + ".max-retry", "3",
                PREFIX + ".backoff", "10s,20s"));

        assertThat(properties.maxRetry()).isEqualTo(3);
        assertThat(properties.backoff()).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("무작위 값이 중간이면 간격 그대로이고 양끝이면 지터 비율만큼 줄고 늘어난다")
    void 지터_범위() {
        PaymentRecheckProperties properties = bind(Map.of());

        assertThat(properties.delayAfter(1, 0.5)).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.delayAfter(1, 0.0)).isEqualTo(Duration.ofSeconds(108));
        assertThat(properties.delayAfter(1, 1.0)).isEqualTo(Duration.ofSeconds(132));
    }

    @Test
    @DisplayName("n번째 시도 뒤에는 n번째 간격을 쓰고 목록이 끝나면 마지막 값을 유지한다")
    void 간격_순서() {
        PaymentRecheckProperties properties = bind(Map.of());

        assertThat(properties.delayAfter(2, 0.5)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.delayAfter(3, 0.5)).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.delayAfter(4, 0.5)).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.delayAfter(9, 0.5)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("기본값에서 지터가 가장 길게 붙어도 총 재조회 기간이 1시간을 넘지 않는다")
    void 총_기간_상한() {
        PaymentRecheckProperties properties = bind(Map.of());

        Duration total = properties.firstDelay()
                .plus(properties.delayAfter(1, 1.0))
                .plus(properties.delayAfter(2, 1.0))
                .plus(properties.delayAfter(3, 1.0))
                .plus(properties.delayAfter(4, 1.0));

        assertThat(total).isLessThan(Duration.ofHours(1));
    }

    @Test
    @DisplayName("시도 횟수가 1보다 작으면 간격을 계산할 수 없다")
    void 시도_횟수_검증() {
        PaymentRecheckProperties properties = bind(Map.of());

        assertThatThrownBy(() -> properties.delayAfter(0, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
