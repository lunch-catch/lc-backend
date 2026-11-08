package com.lunchcatch.adserving.impression.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.global.config.BacklogMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 카운터가 0 으로 있어야 사건이 없을 때 대시보드가 "No data" 가 아니라 0 을 그린다(#1088)
class ImpressionCounterRegistrationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void assertZero(List<String> names) {
        for (String name : names) {
            assertThat(registry.get(name).counter().count()).as(name).isZero();
        }
    }

    @Test
    void 노출_저장의_버림_실패_유실_불일치_카운터를_0으로_만든다() {
        new ImpressionWriteBuffer(null, null, null, null, registry, new BacklogMetrics(registry, Clock.systemUTC()));

        assertZero(List.of(ImpressionWriteBuffer.DROPPED_METRIC, ImpressionWriteBuffer.WRITE_FAILED_METRIC,
                ImpressionWriteBuffer.LOST_METRIC, ImpressionWriteBuffer.CAMPAIGN_MISMATCH_METRIC));
    }

    @Test
    void 릴레이의_넘김_실패_오류_카운터를_0으로_만든다() {
        new ImpressionDispatchService(null, null, null, null, registry, Clock.systemUTC(),
                new BacklogMetrics(registry, Clock.systemUTC()));

        assertZero(List.of(ImpressionDispatchService.DISPATCHED_METRIC, ImpressionDispatchService.FAILED_METRIC,
                ImpressionDispatchService.ERROR_METRIC));
    }
}
