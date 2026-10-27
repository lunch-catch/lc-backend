package com.lunchcatch.coupon.service;

import com.lunchcatch.coupon.config.CouponProperties;
import com.lunchcatch.coupon.entity.CouponEvent;
import com.lunchcatch.coupon.entity.CouponEventStatus;
import com.lunchcatch.coupon.entity.CouponInventoryStatus;
import com.lunchcatch.coupon.repository.CouponEventRepository;
import com.lunchcatch.coupon.repository.CouponInventoryRepository;
import com.lunchcatch.coupon.repository.CouponSequenceRepository;
import com.lunchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 차단된 이벤트의 Redis 큐를 DB 기준으로 다시 만든다.
 * 별도 스케줄러 없이 차단을 발견한 후속 발급 요청이 recover를 부른다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CouponSequenceRecoveryService {

    private final CouponProperties properties;

    private final CouponEventRepository events;
    private final CouponInventoryRepository inventories;
    private final CouponSequenceRepository sequences;
    private final CouponSequenceService sequenceService;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    // 이 서버에서 지금 복구 중인 이벤트 ID이며 같은 이벤트를 두 스레드가 함께 복구하지 않게 한다.
    private final Set<Long> recovering = ConcurrentHashMap.newKeySet();
    // 복구에 실패한 이벤트를 다시 시도할 수 있는 시각(System.nanoTime 기준)이다.
    private final ConcurrentHashMap<Long, Long> retryAfter = new ConcurrentHashMap<>();

    /*
     * 이벤트를 배타 잠금하고 DB의 AVAILABLE 순번과 ISSUED 수로 Redis 큐를 다시 만든다.
     * 배타 잠금은 진행 중인 발급 묶음이 공유 잠금을 풀 때까지 기다리므로 커밋된 결과만 읽는다.
     * 두 수의 합이 이벤트 발급 수량과 다르면 큐를 만들지 않고 실패로 남긴다.
     * 성공하면 이 서버의 차단을 풀고, 실패하면 차단을 유지한 채 설정된 시간 동안 다시 시도하지 않는다.
     */
    public boolean recover(long eventId) {
        return recover(eventId, System.nanoTime() + properties.issue().batch().waitTimeout().toNanos());
    }

    // 호출자와 같은 처리 기한을 사용하며 실제 재구축과 트랜잭션 종료에 성공한 경우만 true를 반환한다.
    public boolean recover(long eventId, long deadlineNanos) {
        long nextRetryAt = retryAfter.getOrDefault(eventId, Long.MIN_VALUE);

        // 이전 복구 실패 후 재시도 대기시간이 안 지났으면 이번 복구 시도를 건너뛴다.
        if (System.nanoTime() < nextRetryAt || deadlineNanos - System.nanoTime() <= 0
                || Thread.currentThread().isInterrupted()) {
            return false;
        }

        // 이 서버에서 다른 스레드가 이미 같은 이벤트를 복구 중이면 건너뛴다.
        if (!recovering.add(eventId)) {
            return false;
        }
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            transaction.setTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining - 1) + 1));
            boolean rebuilt = Boolean.TRUE.equals(transaction.execute(status -> {
                CouponEvent event = events.findForRecovery(eventId).orElseThrow();
                long expiresAt = expiresAt(event);
                if (clock.millis() >= expiresAt || deadlineNanos - System.nanoTime() <= 0) {
                    return false;
                }
                InventorySnapshot inventory = readInventory(eventId, event);
                if (deadlineNanos - System.nanoTime() <= 0) {
                    throw new IllegalStateException("Coupon recovery deadline expired");
                }
                sequences.rebuild(eventId, inventory.available(), inventory.completed(), expiresAt);
                return true;
            }));
            if (!rebuilt) {
                return false;
            }
            sequenceService.resume(eventId);
            retryAfter.remove(eventId);
            log.info("event=COUPON_ISSUANCE_RECOVERY_DONE couponEventId={}", eventId);
            return true;
        } catch (RuntimeException error) {
            sequenceService.block(eventId, error);
            retryAfter.put(eventId, System.nanoTime() + properties.sequence().retryDelay().toNanos());
            CouponFailureDetails details = CouponFailureDetails.from(error);
            log.warn("event=COUPON_ISSUANCE_RECOVERY_FAILED couponEventId={} exceptionType={} reason={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                    eventId, details.exceptionType(), CouponFailureDetails.reason(error), details.httpStatus(),
                    details.sqlState(), details.sqlErrorCode(), details.origin());
            return false;
        } finally {
            recovering.remove(eventId);
        }
    }

    /*
     * 발급 오픈 전 배치가 호출자의 트랜잭션 안에서 회차를 배타 잠금하고 Redis 큐를 DB 기준으로 미리 만든다.
     * ACTIVE와 PAUSED 회차만 만들며 큐가 이미 READY나 SOLD_OUT이면 진행 중인 발급을 지우지 않도록 그대로 둔다.
     * 차단은 걸지 않으므로 실패해도 그 회차는 첫 발급 요청의 복구가 다시 만든다.
     */
    public boolean prepare(long eventId) {
        CouponEvent event = events.findForRecovery(eventId).orElseThrow();
        if (event.getStatus() != CouponEventStatus.ACTIVE && event.getStatus() != CouponEventStatus.PAUSED) {
            return false;
        }
        long expiresAt = expiresAt(event);
        if (clock.millis() >= expiresAt || sequences.availability(eventId) >= 0) {
            return false;
        }
        InventorySnapshot inventory = readInventory(eventId, event);
        sequences.rebuild(eventId, inventory.available(), inventory.completed(), expiresAt);
        return true;
    }

    // 큐의 키는 영업일의 발급 종료 시각에서 보존 여유만큼 지난 뒤 만료된다.
    private long expiresAt(CouponEvent event) {
        return event.getBusinessDate().atTime(properties.issueEndTime()).atZone(ClockConfig.ZONE)
                .toInstant().plus(properties.sequence().retentionGrace()).toEpochMilli();
    }

    // DB의 AVAILABLE 순번과 ISSUED 수를 읽고 두 수의 합이 회차의 발급 수량과 다르면 큐를 만들지 않는다.
    private InventorySnapshot readInventory(long eventId, CouponEvent event) {
        List<Integer> available = inventories.findAvailableSequences(eventId);
        long completed = inventories.countByCouponEventIdAndStatus(eventId, CouponInventoryStatus.ISSUED);
        if (available.size() + completed != event.getIssueQuantity()) {
            throw new IllegalStateException("Coupon inventory count differs from event quantity");
        }
        return new InventorySnapshot(available, completed);
    }

    // 큐를 다시 만들 때 쓰는 회차의 남은 순번과 발급된 수다.
    private record InventorySnapshot(List<Integer> available, long completed) { }
}
