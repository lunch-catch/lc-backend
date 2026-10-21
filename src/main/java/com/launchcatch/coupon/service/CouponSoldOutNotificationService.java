package com.launchcatch.coupon.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.launchcatch.adserving.contract.CandidateStateUpdater;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/*
 * 소진 통보를 요청과 분리하고 이 서버에서는 같은 이벤트를 한 작업자만 처리한다.
 * 대기 작업은 최대 2,000개이며 성공한 이벤트는 하루 동안 다시 통보하지 않는다.
 * 실패한 작업은 5초 간격으로 한 시간까지 재시도하며 서버 종료 때 남은 작업은 로그로 남긴다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CouponSoldOutNotificationService implements SmartLifecycle {

    private static final int MAX_PENDING = 2000;
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(5);
    private static final Duration RETRY_WINDOW = Duration.ofHours(1);

    private final CouponLookUpService lookUpService;
    private final CandidateStateUpdater candidateStateUpdater;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Cache<Long, Boolean> completed = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofDays(1)).build();
    private volatile boolean running;
    private ScheduledExecutorService executor;

    // 접수만 메모리에 기록하고 DB 조회와 통보가 끝나기를 기다리지 않는다.
    public synchronized void request(long eventId) {
        if (completed.getIfPresent(eventId) != null || pending.containsKey(eventId)) {
            return;
        }
        if (!running || pending.size() >= MAX_PENDING) {
            log.error("event=COUPON_SOLD_OUT_NOTIFICATION_REJECTED couponEventId={} pending={}", eventId, pending.size());
            return;
        }
        Instant now = clock.instant();
        pending.put(eventId, new Pending(now, now.plus(RETRY_WINDOW)));
    }

    // DB에서 확인한 마지막 발급 시각으로 통보하며 실패한 이벤트만 다음 실행으로 넘긴다.
    void processPending() {
        for (var entry : pending.entrySet()) {
            long eventId = entry.getKey();
            Pending task = entry.getValue();
            Instant now = clock.instant();
            if (!now.isBefore(task.expiresAt())) {
                pending.remove(eventId);
                log.error("event=COUPON_SOLD_OUT_NOTIFICATION_EXPIRED couponEventId={}", eventId);
                continue;
            }
            if (now.isBefore(task.retryAt())) {
                continue;
            }
            try {
                CouponLookUpService.SoldOut soldOut = lookUpService.findSoldOut(eventId).orElseThrow(
                        () -> new IllegalStateException("Committed coupon exhaustion is not visible"));
                candidateStateUpdater.markSoldOut(soldOut.campaignId(), soldOut.businessDate(), soldOut.soldOutAt());
                completed.put(eventId, true);
                pending.remove(eventId);
                log.info("event=COUPON_SOLD_OUT_NOTIFIED couponEventId={}", eventId);
            } catch (RuntimeException error) {
                pending.put(eventId, new Pending(clock.instant().plus(RETRY_INTERVAL), task.expiresAt()));
                log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED couponEventId={}", eventId, error);
            }
        }
    }

    // 전역 배치 스케줄러를 켜지 않고 소진 통보 전용 작업자 하나만 실행한다.
    @Override
    public synchronized void start() {
        if (running || executor != null && !executor.isTerminated()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("coupon-sold-out").daemon(true).factory());
        running = true;
        executor.scheduleWithFixedDelay(this::processPending, 1, 1, TimeUnit.SECONDS);
    }

    // 실행 중인 통보는 최대 5초 기다리고 남은 작업 수를 기록한다.
    @Override
    public void stop() {
        ScheduledExecutorService stopping;
        synchronized (this) {
            running = false;
            stopping = executor;
        }
        if (stopping == null) {
            return;
        }
        stopping.shutdown();
        try {
            if (!stopping.awaitTermination(5, TimeUnit.SECONDS)) {
                log.error("event=COUPON_SOLD_OUT_NOTIFICATION_STOP_TIMEOUT pending={}", pending.size());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!pending.isEmpty()) {
            log.error("event=COUPON_SOLD_OUT_NOTIFICATION_UNFINISHED pending={}", pending.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record Pending(Instant retryAt, Instant expiresAt) { }
}
