package com.launchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class BillingSchedulePropertiesTest {

    private static BillingScheduleProperties bind(Map<String, String> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bind("billing.schedule", BillingScheduleProperties.class).get();
    }

    private static Map<String, String> valid() {
        Map<String, String> source = new HashMap<>();
        source.put("billing.schedule.serving-start", "10:00");
        source.put("billing.schedule.serving-end", "12:59");
        source.put("billing.schedule.impression-valid-for", "30m");
        source.put("billing.schedule.hourly-weights[0]", "34");
        source.put("billing.schedule.hourly-weights[1]", "33");
        source.put("billing.schedule.hourly-weights[2]", "33");
        return source;
    }

    @Test
    void 정상_값이_바인딩된다() {
        BillingScheduleProperties properties = bind(valid());

        assertThat(properties.startTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(properties.endTime()).isEqualTo(LocalTime.of(12, 59));
        assertThat(properties.impressionValidFor()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.hourlyWeights()).containsExactly(34, 33, 33);
    }

    @Test
    void 아무것도_안_주면_시작값으로_채워진다() {
        BillingScheduleProperties properties = bind(Map.of("billing.schedule.impression-valid-for", "30m"));

        assertThat(properties.servingStart()).isEqualTo("10:00");
        assertThat(properties.servingEnd()).isEqualTo("12:59");
        assertThat(properties.hourlyWeights()).containsExactly(34, 33, 33);
    }

    @Test
    void 서빙_종료_뒤_고정_시각은_종료_시각의_다음_분이다() {
        assertThat(bind(valid()).fixedFrom()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void 종료_시각이_바뀌면_고정_시각도_따라간다() {
        BillingScheduleProperties properties = new BillingScheduleProperties("10:00", "11:30", Duration.ofMinutes(30), List.of(50, 50));

        assertThat(properties.fixedFrom()).isEqualTo(LocalTime.of(11, 31));
    }

    @Test
    void 시작이_종료보다_앞서지_않으면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("12:59", "10:00", Duration.ofMinutes(30), List.of(34, 33, 33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serving-start");
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "10:00", Duration.ofMinutes(30), List.of(100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 시각_형식이_틀리면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("열시", "12:59", Duration.ofMinutes(30), List.of(34, 33, 33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serving-start");
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", null, Duration.ofMinutes(30), List.of(34, 33, 33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serving-end");
    }

    @Test
    void 가중치_개수가_서빙_시간대_수와_다르면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(50, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개수");
    }

    @Test
    void 가중치에_음수가_있으면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(50, -10, 60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이상");
    }

    @Test
    void 가중치_합이_0이면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), List.of(0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("합");
    }

    @Test
    void 유효_시간이_0_이하면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", Duration.ZERO, List.of(34, 33, 33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("impression-valid-for");
    }

    @Test
    void 필수_값이_없으면_거부한다() {
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", null, List.of(34, 33, 33)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BillingScheduleProperties("10:00", "12:59", Duration.ofMinutes(30), null))
                .isInstanceOf(NullPointerException.class);
    }
}
