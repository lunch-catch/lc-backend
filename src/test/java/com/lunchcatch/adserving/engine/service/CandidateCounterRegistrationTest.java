package com.lunchcatch.adserving.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

// 기동 때 카운터가 0 으로 있어야 사건이 없을 때 대시보드가 "No data" 가 아니라 0 을 그린다(#1088)
class CandidateCounterRegistrationTest {

    @Test
    void 예산을_읽지_못한_횟수를_0으로_만든다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new CandidateBudgetService(null, null, null, registry, null).registerCounters();

        assertThat(registry.get(CandidateBudgetService.BUDGET_UNAVAILABLE_METRIC).counter().count()).isZero();
    }
}
