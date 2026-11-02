package com.lunchcatch.adserving.exclusion.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

// 기동 때 쓰임새마다 카운터가 0 으로 있어야 Valkey 가 멀쩡할 때 대시보드가 "No data" 가 아니라 0 을 그린다(#1088)
class BypassCounterRegistrationTest {

    @Test
    void Valkey_우회_카운터를_쓰임새마다_0으로_만든다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ExclusionRepository(null, registry).registerCounters();

        assertThat(ExclusionRepository.BYPASS_USES).containsExactlyInAnyOrder("exclusion", "feed_filter",
                "feed_rate_limit", "feed_wishes", "impression_count", "serve_frequency", "serve_record");
        for (String use : ExclusionRepository.BYPASS_USES) {
            assertThat(registry.get(ExclusionRepository.BYPASS_METRIC).tag("use", use).counter().count())
                    .as(use).isZero();
        }
    }
}
