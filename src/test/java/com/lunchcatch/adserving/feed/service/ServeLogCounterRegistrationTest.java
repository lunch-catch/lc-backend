package com.lunchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.adserving.config.AdservingTuningDefaults;
import com.lunchcatch.global.config.BacklogMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 카운터가 0 으로 있어야 사건이 없을 때 대시보드가 "No data" 가 아니라 0 을 그린다(#1088)
class ServeLogCounterRegistrationTest {

    @Test
    void 서빙_로그의_버림_실패_방문_실패_카운터를_0으로_만든다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ServeLogBuffer(null, null, registry, new BacklogMetrics(registry, Clock.systemUTC()),
                AdservingTuningDefaults.serveLogBuffer());

        for (String name : List.of(ServeLogBuffer.DROPPED_METRIC, ServeLogBuffer.FAILED_METRIC,
                ServeLogBuffer.VISIT_FAILED_METRIC)) {
            assertThat(registry.get(name).counter().count()).as(name).isZero();
        }
    }
}
