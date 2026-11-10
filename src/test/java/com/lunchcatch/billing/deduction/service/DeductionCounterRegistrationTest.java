package com.lunchcatch.billing.deduction.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 결과 라벨마다 카운터가 0 으로 있어야 경보가 첫 사건의 증가를 센다(#1027)
class DeductionCounterRegistrationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void assertZero(String name, String tagKey, List<String> values) {
        for (String value : values) {
            assertThat(registry.get(name).tag(tagKey, value).counter().count()).as(name + " " + value).isZero();
        }
    }

    @Test
    void 노출_차감의_되돌리기_계획없음_묶음실패_묶음대체_카운터를_0으로_만든다() {
        new PointDeductorImpl(null, null, null, null, null, null, null, null, registry, null, null, null)
                .registerCounters();

        assertZero(PointDeductorImpl.UNDO_METRIC, "result", List.of("ok", "failed"));
        assertThat(registry.get(PointDeductorImpl.NO_PLAN_METRIC).counter().count()).isZero();
        assertZero(PointDeductorImpl.BATCH_FAILED_METRIC, "reason", List.of("owner_group", "unknown_campaign"));
        assertThat(registry.get(PointDeductorImpl.BATCH_FALLBACK_METRIC).counter().count()).isZero();
    }

    @Test
    void 해시_재구성_카운터를_0으로_만든다() {
        new SpendHashRebuildService(null, null, null, registry).registerCounters();

        assertThat(registry.get(SpendHashRebuildService.REBUILD_METRIC).counter().count()).isZero();
    }
}
