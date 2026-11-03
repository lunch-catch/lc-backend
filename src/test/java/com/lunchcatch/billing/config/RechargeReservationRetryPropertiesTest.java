package com.lunchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RechargeReservationRetryPropertiesTest {

    private static final String PREFIX = "billing.recharge-reservation";

    private static RechargeReservationRetryProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate(PREFIX, RechargeReservationRetryProperties.class);
    }

    private static RechargeReservationRetryProperties defaults() {
        return bind(Map.of());
    }

    @Test
    void 아무_값도_주지_않으면_간격_1_2_5_10분과_배치_20건과_선점_5분과_실행_상한_50초다() {
        RechargeReservationRetryProperties properties = defaults();

        assertThat(properties.backoff()).containsExactly(
                Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10));
        assertThat(properties.batchSize()).isEqualTo(20);
        assertThat(properties.claimLease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.maxRunTime()).isEqualTo(Duration.ofSeconds(50));
    }

    @Test
    void 설정으로_간격_목록을_바꾼다() {
        RechargeReservationRetryProperties properties = bind(Map.of(PREFIX + ".backoff", "30s,3m"));

        assertThat(properties.backoff()).containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(3));
    }

    @Test
    void n번째_실패_뒤에는_n번째_간격을_쓴다() {
        RechargeReservationRetryProperties properties = defaults();

        assertThat(properties.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.delayAfter(3)).isEqualTo(Duration.ofMinutes(5));
    }

    // 횟수 상한이 없으므로 목록이 끝난 뒤에도 계속 시도한다
    @Test
    void 목록이_끝나면_마지막_간격을_유지한다() {
        RechargeReservationRetryProperties properties = defaults();

        assertThat(properties.delayAfter(4)).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.delayAfter(100)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 실패_횟수가_1보다_작으면_거절한다() {
        RechargeReservationRetryProperties properties =
                new RechargeReservationRetryProperties(List.of(Duration.ofMinutes(1)), 20, Duration.ofMinutes(5),
                        Duration.ofSeconds(50));

        assertThatThrownBy(() -> properties.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
