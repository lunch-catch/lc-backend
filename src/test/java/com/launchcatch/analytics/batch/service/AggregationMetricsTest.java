package com.launchcatch.analytics.batch.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AggregationMetricsTest {

    private SimpleMeterRegistry registry;
    private AggregationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AggregationMetrics(registry);
    }

    @Test
    void 단계_소요_시간은_step_라벨로_기록한다() {
        metrics.recordStep("SettlementMetricsStep", Duration.ofMillis(1500));

        assertThat(registry.get("analytics_aggregation_step_seconds")
                .tag("step", "SettlementMetricsStep").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(1500.0);
    }

    @Test
    void 쓴_행_수는_단계마다_게이지_하나에_덮어쓴다() {
        metrics.recordRows("SettlementMetricsStep", 10);
        metrics.recordRows("SettlementMetricsStep", 7);
        metrics.recordRows("MemberMetricsStep", 3);

        assertThat(registry.get("analytics_aggregation_rows").tag("step", "SettlementMetricsStep")
                .gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("analytics_aggregation_rows").tag("step", "MemberMetricsStep")
                .gauge().value()).isEqualTo(3.0);
    }

    @Test
    void 결과_횟수는_result_라벨별로_센다() {
        metrics.countResult("success");
        metrics.countResult("success");
        metrics.countResult("failed");

        assertThat(registry.get("analytics_aggregation_total").tag("result", "success")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("analytics_aggregation_total").tag("result", "failed")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void 미처리_날짜_수는_게이지가_가장_최근_값을_보인다() {
        metrics.recordPendingDates(4);
        metrics.recordPendingDates(2);

        assertThat(registry.get("analytics_aggregation_pending_dates").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void 정합성_위반_수는_check_라벨별로_덮어쓴다() {
        metrics.recordViolations("V-1", 5);
        metrics.recordViolations("V-1", 0);

        assertThat(registry.get("analytics_consistency_violations").tag("check", "V-1")
                .gauge().value()).isEqualTo(0.0);
    }
}
