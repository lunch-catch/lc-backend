package com.lunchcatch.coupon.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
 * 쿠폰의 백그라운드 대기열 세 곳이 얼마나 밀렸는지 내보낸다.
 * 대기열마다 지금 기다리는 수와 가장 오래 기다린 건의 대기 시간(초)을 둔다.
 * 기준값은 coupon_구조와_동작_설계.md 11장이 갖는다.
 */
@Component
@RequiredArgsConstructor
public class CouponBackgroundMetrics {

    static final String ISSUE_QUEUE_SIZE = "coupon_issue_queue_size";
    static final String ISSUE_QUEUE_OLDEST_WAIT = "coupon_issue_queue_oldest_wait_seconds";
    static final String RECOVERY_QUEUE_SIZE = "coupon_recovery_queue_size";
    static final String RECOVERY_QUEUE_OLDEST_WAIT = "coupon_recovery_queue_oldest_wait_seconds";
    static final String SOLD_OUT_PENDING_SIZE = "coupon_sold_out_pending_size";
    static final String SOLD_OUT_PENDING_OLDEST_WAIT = "coupon_sold_out_pending_oldest_wait_seconds";

    private final MeterRegistry registry;
    private final CouponIssueBatcher batcher;
    private final CouponCommandService commandService;
    private final CouponSoldOutNotificationService soldOutNotifications;

    // 지표를 읽을 때마다 대기열의 현재 값을 계산하도록 게이지를 등록한다.
    @PostConstruct
    void register() {
        Gauge.builder(ISSUE_QUEUE_SIZE, batcher, CouponIssueBatcher::queuedCount)
                .description("발급 작업자를 기다리는 요청 수").register(registry);
        Gauge.builder(ISSUE_QUEUE_OLDEST_WAIT, batcher, CouponIssueBatcher::oldestQueuedSeconds)
                .description("발급 작업자를 가장 오래 기다린 요청의 대기 시간(초)").register(registry);
        Gauge.builder(RECOVERY_QUEUE_SIZE, commandService, CouponCommandService::recoveryQueueSize)
                .description("복구 작업자를 기다리는 작업 수").register(registry);
        Gauge.builder(RECOVERY_QUEUE_OLDEST_WAIT, commandService, CouponCommandService::oldestRecoveryWaitSeconds)
                .description("복구 작업자를 가장 오래 기다린 작업의 대기 시간(초)").register(registry);
        Gauge.builder(SOLD_OUT_PENDING_SIZE, soldOutNotifications, CouponSoldOutNotificationService::pendingCount)
                .description("광고 후보에 소진을 통보하지 못한 이벤트 수").register(registry);
        Gauge.builder(SOLD_OUT_PENDING_OLDEST_WAIT, soldOutNotifications, CouponSoldOutNotificationService::oldestPendingSeconds)
                .description("가장 오래 통보를 기다린 이벤트의 대기 시간(초)").register(registry);
    }
}
