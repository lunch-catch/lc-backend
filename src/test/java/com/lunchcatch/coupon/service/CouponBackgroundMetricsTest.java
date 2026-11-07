package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("쿠폰 백그라운드 대기열 지표")
class CouponBackgroundMetricsTest {

    // 세 대기열의 수와 가장 오래 기다린 시간이 정해진 이름의 게이지로 나가고, 읽을 때마다 대기열의 현재 값을 보여 준다.
    @Test
    void 세_대기열의_수와_가장_오래_기다린_시간을_게이지로_내보낸다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CouponIssueBatcher batcher = mock(CouponIssueBatcher.class);
        CouponCommandService commandService = mock(CouponCommandService.class);
        CouponSoldOutNotificationService soldOut = mock(CouponSoldOutNotificationService.class);
        when(batcher.queuedCount()).thenReturn(7, 9);
        when(batcher.oldestQueuedSeconds()).thenReturn(0.4);
        when(commandService.recoveryQueueSize()).thenReturn(2);
        when(commandService.oldestRecoveryWaitSeconds()).thenReturn(1.5);
        when(soldOut.pendingCount()).thenReturn(3);
        when(soldOut.oldestPendingSeconds()).thenReturn(6.0);

        new CouponBackgroundMetrics(registry, batcher, commandService, soldOut).register();

        assertThat(registry.get(CouponBackgroundMetrics.ISSUE_QUEUE_SIZE).gauge().value()).isEqualTo(7);
        assertThat(registry.get(CouponBackgroundMetrics.ISSUE_QUEUE_SIZE).gauge().value()).isEqualTo(9);
        assertThat(registry.get(CouponBackgroundMetrics.ISSUE_QUEUE_OLDEST_WAIT).gauge().value()).isEqualTo(0.4);
        assertThat(registry.get(CouponBackgroundMetrics.RECOVERY_QUEUE_SIZE).gauge().value()).isEqualTo(2);
        assertThat(registry.get(CouponBackgroundMetrics.RECOVERY_QUEUE_OLDEST_WAIT).gauge().value()).isEqualTo(1.5);
        assertThat(registry.get(CouponBackgroundMetrics.SOLD_OUT_PENDING_SIZE).gauge().value()).isEqualTo(3);
        assertThat(registry.get(CouponBackgroundMetrics.SOLD_OUT_PENDING_OLDEST_WAIT).gauge().value()).isEqualTo(6.0);
        registry.close();
    }
}
