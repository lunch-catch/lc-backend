package com.launchcatch.adserving.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PacingPropertiesTest {

    private static PacingProperties bind(Map<String, String> source) {
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        return binder.bindOrCreate("adserving.pacing", PacingProperties.class);
    }

    @Test
    void 정상_값이_바인딩된다() {
        Map<String, String> source = new HashMap<>();
        for (int hour = 0; hour < PacingProperties.HOURS; hour++) {
            source.put("adserving.pacing.hourly-weights[" + hour + "]", String.valueOf(hour));
        }

        PacingProperties properties = bind(source);

        assertThat(properties.hourlyWeights()).hasSize(24).startsWith(0, 1, 2).endsWith(22, 23);
    }

    @Test
    void 아무것도_안_주면_시작값_24개로_채워진다() {
        PacingProperties properties = bind(Map.of());

        assertThat(properties.hourlyWeights()).containsExactly(
                2, 1, 1, 1, 1, 1, 1, 3, 4, 5, 8, 10, 9, 7, 4, 4, 4, 5, 7, 8, 6, 4, 2, 2);
        assertThat(properties.hourlyWeights().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void 가중치_개수가_24가_아니면_거부한다() {
        assertThatThrownBy(() -> new PacingProperties(List.of(34, 33, 33)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개수");
    }

    @Test
    void 가중치에_음수가_있으면_거부한다() {
        List<Integer> values = new ArrayList<>(Collections.nCopies(24, 1));
        values.set(5, -1);

        assertThatThrownBy(() -> new PacingProperties(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 이상");
    }

    @Test
    void 가중치_합이_0이면_거부한다() {
        assertThatThrownBy(() -> new PacingProperties(Collections.nCopies(24, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("합");
    }

    @Test
    void 가중치가_없으면_거부한다() {
        assertThatThrownBy(() -> new PacingProperties(null))
                .isInstanceOf(NullPointerException.class);
    }
}
