package com.lunchcatch.coupon.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lunchcatch.adserving.contract.CandidateStateUpdater;
import com.lunchcatch.coupon.config.CouponProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/*
 * 소진 통보를 요청과 분리하고 이 서버에서는 같은 이벤트를 한 작업자만 처리한다.
 * 대기 작업 수와 성공한 이벤트를 보관하는 기간은 설정에서 읽는다.
 * 실패한 작업은 설정된 간격과 기간 안에서 재시도하며 서버 종료 때 남은 작업은 로그로 남긴다.
 */
@Service
@Slf4j
public class CouponSoldOutNotificationService implements SmartLifecycle {

    private final CouponProperties properties;

    private final CouponLookUpService lookUpService;
    private final CandidateStateUpdater candidateStateUpdater;
    private final Clock clock;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Cache<Long, Boolean> completed;
    private volatile boolean running;
    private ScheduledExecutorService executor;

    public CouponSoldOutNotificationService(CouponProperties properties, CouponLookUpService lookUpService,
            CandidateStateUpdater candidateStateUpdater, Clock clock) {
        this.properties = properties;
        this.lookUpService = lookUpService;
        this.candidateStateUpdater = candidateStateUpdater;
        this.clock = clock;
        completed = Caffeine.newBuilder().maximumSize(properties.soldOut().completedCacheSize())
                .expireAfterWrite(properties.soldOut().completedCacheTtl()).build();
    }

    // 접수만 메모리에 기록하고 DB 조회와 통보가 끝나기를 기다리지 않는다.
    public synchronized void request(long eventId) {
        if (completed.getIfPresent(eventId) != null || pending.containsKey(eventId)) {
            return;
        }
        if (!running || pending.size() >= properties.soldOut().maxPending()) {
            log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED reason=REJECTED couponEventId={} pendingCount={} requestTraceIds={}",
                    eventId, pending.size(), CouponLogContext.requestTraceIds());
            return;
        }
        Instant now = clock.instant();
        pending.put(eventId, new Pending(now, now.plus(properties.soldOut().retryWindow()), CouponLogContext.capture()));
    }

    // DB에서 확인한 마지막 발급 시각으로 통보하며 실패한 이벤트만 다음 실행으로 넘긴다.
    void processPending() {
        for (var entry : pending.entrySet()) {
            long eventId = entry.getKey();
            Pending task = entry.getValue();
            try (var ignored = task.logContext().open()) {
                Instant now = clock.instant();
                if (!now.isBefore(task.expiresAt())) {
                    pending.remove(eventId);
                    log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED reason=RETRY_EXPIRED couponEventId={} requestTraceIds={}",
                            eventId, CouponLogContext.requestTraceIds());
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
                    log.info("event=COUPON_SOLD_OUT_NOTIFICATION_DONE couponEventId={}", eventId);
                } catch (RuntimeException error) {
                    pending.put(eventId, new Pending(clock.instant().plus(properties.soldOut().retryInterval()), task.expiresAt(), task.logContext()));
                    CouponFailureDetails details = CouponFailureDetails.from(error);
                    log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED reason=RETRY_SCHEDULED couponEventId={} fallback=RETRY requestTraceIds={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                            eventId, CouponLogContext.requestTraceIds(), details.exceptionType(), details.httpStatus(),
                            details.sqlState(), details.sqlErrorCode(), details.origin(), details.stackTrace());
                }
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
        executor.scheduleWithFixedDelay(this::processPending, properties.soldOut().pollInterval().toNanos(),
                properties.soldOut().pollInterval().toNanos(), TimeUnit.NANOSECONDS);
    }

    // 실행 중인 통보는 설정된 종료 시간까지 기다리고 남은 작업 수를 기록한다.
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
            if (!stopping.awaitTermination(properties.soldOut().shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED reason=STOP_TIMEOUT pendingCount={}", pending.size());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        if (!pending.isEmpty()) {
            log.error("event=COUPON_SOLD_OUT_NOTIFICATION_FAILED reason=UNFINISHED pendingCount={}", pending.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record Pending(Instant retryAt, Instant expiresAt, CouponLogContext logContext) { }
}
