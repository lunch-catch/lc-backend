package com.launchcatch.coupon.service;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponSequenceRepository;
import com.launchcatch.coupon.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.model.CouponReservation;
import com.launchcatch.coupon.model.CouponReservationOutcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * 발급 이벤트의 재고 순번을 Redis에서 예약하고 완료·반납하며 이상이 생긴 이벤트를 차단한다.
 * 기준 데이터는 DB이고 Redis는 같은 재고 행을 두 요청이 동시에 고르지 않게 순번을 나눠 주는 역할만 한다.
 * 차단은 이 서버의 메모리 표시와 Redis 큐의 BLOCKED 상태 두 곳에 남긴다.
 * 차단된 이벤트는 CouponSequenceRecoveryService가 DB 기준으로 큐를 다시 만든 뒤 resume으로 풀린다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CouponSequenceService {

    private final CouponSequenceRepository sequences;
    // 이 서버에서 차단한 이벤트 ID이며 Redis가 응답하지 않아도 새 발급을 막는다.
    private final Set<Long> blockedEvents = ConcurrentHashMap.newKeySet();

    // 예약한 뒤 이 시간이 지나도 완료나 반납이 없으면 응답을 잃은 예약으로 보고 큐를 차단한다.
    @Value("${coupon.sequence.stale-after:30s}")
    private Duration staleAfter;

    // 이 서버가 차단한 이벤트면 Redis를 부르지 않고 바로 ISSUANCE_BUSY로 거절한다.
    public void checkAdmission(long eventId) {
        if (blockedEvents.contains(eventId)) {
            throw rejectForRecovery(eventId);
        }
    }

    /*
     * 묶음 발급이 이벤트의 DB 공유 잠금을 확보한 트랜잭션 안에서 count장을 Redis 호출 한 번으로 예약한다.
     * 결과는 요청 순서를 따르며 예약하지 못한 자리에는 소진이면 INVENTORY_EXHAUSTED, 그 밖에는 ISSUANCE_BUSY를 담는다.
     * 큐가 차단 상태이거나 오래된 예약, 수량 불일치를 만나면 이벤트를 차단해 다음 요청이 복구하게 한다.
     * 롤백되면 받은 순번을 모두 반납하고 결과를 모르면 이벤트를 차단한다.
     */
    public List<CouponReservationOutcome> reserveAll(long eventId, int count) {
        checkAdmission(eventId);
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        List<Long> results;
        try {
            results = sequences.reserveAll(eventId, ids, staleAfter.toMillis());
        } catch (RuntimeException error) {
            block(eventId, error);
            throw rejectForRecovery(eventId);
        }
        return reservationOutcomes(eventId, ids, results);
    }

    /*
     * 소진 조회 뒤 이 서버가 차단한 이벤트는 Redis를 부르지 않고 그 이벤트의 요청만 복구 대상으로 돌려준다.
     * 모든 응답을 확인하기 전 오류가 나면 일부 예약이 실행됐을 수 있으므로 예약을 요청한 이벤트를 모두 차단한다.
     */
    public Map<Long, List<CouponReservationOutcome>> reserveAll(Map<Long, Integer> counts) {
        Map<Long, List<CouponReservationOutcome>> reserved = new LinkedHashMap<>();
        Map<Long, List<String>> ids = new LinkedHashMap<>();
        counts.forEach((eventId, count) -> {
            if (blockedEvents.contains(eventId)) {
                reserved.put(eventId, java.util.Collections.nCopies(count,
                        new CouponReservationOutcome(null, CouponErrorCode.ISSUANCE_BUSY, true, false)));
            } else {
                ids.put(eventId, java.util.stream.IntStream.range(0, count)
                        .mapToObj(index -> UUID.randomUUID().toString()).toList());
            }
        });
        if (ids.isEmpty()) {
            return reserved;
        }
        Map<Long, List<Long>> results;
        try {
            results = sequences.reserveAll(ids, staleAfter.toMillis());
        } catch (RuntimeException error) {
            blockAll(ids.keySet(), error);
            throw rejectForRecovery(ids.keySet().iterator().next());
        }
        ids.forEach((eventId, pending) -> reserved.put(eventId,
                reservationOutcomes(eventId, pending, results.get(eventId))));
        return reserved;
    }

    private List<CouponReservationOutcome> reservationOutcomes(long eventId, List<String> ids, List<Long> results) {
        List<CouponReservationOutcome> reserved = new ArrayList<>(ids.size());
        List<CouponReservation> taken = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            long sequence = results.get(i);
            if (sequence > 0) {
                CouponReservation reservation = new CouponReservation(eventId, Math.toIntExact(sequence), ids.get(i));
                taken.add(reservation);
                reserved.add(new CouponReservationOutcome(reservation, null, false, false));
            } else {
                if (sequence == -1) {
                    block(eventId, null);
                }
                reserved.add(new CouponReservationOutcome(null, sequence == 0
                        ? CouponErrorCode.INVENTORY_EXHAUSTED : CouponErrorCode.ISSUANCE_BUSY, sequence == -1, sequence == -2));
            }
        }
        // 트랜잭션이 끝난 결과를 보고 롤백이면 받은 순번을 돌려놓고 결과를 모르면 이벤트를 차단한다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    taken.forEach(CouponSequenceService.this::release);
                } else if (status != STATUS_COMMITTED) {
                    block(eventId, null);
                }
            }
        });
        return reserved;
    }

    /*
     * 요청을 발급 대기열에 넣기 전에 정상 Redis 정보의 확정 소진만 확인한다.
     * 큐가 없거나 준비 중이면 false를 반환해 이벤트 존재와 상태 검사 및 기존 복구로 넘긴다.
     */
    public boolean isConfirmedSoldOut(long eventId) {
        return readAvailability(eventId) == 0;
    }

    // 묶음 발급은 이벤트를 확인한 뒤 큐가 준비되지 않았으면 차단하고 DB 기준 복구를 요청한다.
    public boolean isSoldOut(long eventId) {
        int availability = readAvailability(eventId);
        if (availability < 0) {
            block(eventId, null);
            throw rejectForRecovery(eventId);
        }
        return availability == 0;
    }

    /*
     * 묶음에 든 이벤트마다 확정 소진과 복구 필요를 따로 판정해 해당 이벤트의 요청에 줄 결과를 돌려준다.
     * 이 서버가 이미 차단했거나 Redis 큐가 준비되지 않은 이벤트만 복구 대상으로 남기고 나머지 이벤트는 계속 발급한다.
     * Redis 호출 자체가 실패하면 어느 이벤트의 상태도 알 수 없으므로 모두 차단하고 묶음을 실패시킨다.
     */
    public Map<Long, CouponIssuanceOutcome> findUnavailableEvents(Set<Long> eventIds) {
        Map<Long, CouponIssuanceOutcome> unavailable = new HashMap<>();
        List<Long> readable = new ArrayList<>();
        for (long eventId : eventIds) {
            if (blockedEvents.contains(eventId)) {
                unavailable.put(eventId, CouponIssuanceOutcome.requiringRecovery());
            } else {
                readable.add(eventId);
            }
        }
        if (readable.isEmpty()) {
            return unavailable;
        }
        Map<Long, Integer> availability;
        try {
            availability = sequences.availability(readable);
        } catch (RuntimeException error) {
            blockAll(eventIds, error);
            throw rejectForRecovery(eventIds.iterator().next());
        }
        Set<Long> notReady = new HashSet<>();
        availability.forEach((eventId, value) -> {
            if (value < 0) {
                notReady.add(eventId);
                unavailable.put(eventId, CouponIssuanceOutcome.requiringRecovery());
            } else if (value == 0) {
                unavailable.put(eventId, CouponIssuanceOutcome.rejected(CouponErrorCode.INVENTORY_EXHAUSTED));
            }
        });
        if (!notReady.isEmpty()) {
            blockAll(notReady, null);
        }
        return unavailable;
    }

    // Redis 조회 장애를 소진으로 바꾸지 않고 해당 이벤트를 차단해 복구하도록 한다.
    private int readAvailability(long eventId) {
        checkAdmission(eventId);
        try {
            return sequences.availability(eventId);
        } catch (RuntimeException error) {
            block(eventId, error);
            throw rejectForRecovery(eventId);
        }
    }

    public int retryAvailability(long eventId) {
        checkAdmission(eventId);
        int availability;
        try {
            availability = sequences.retryAvailability(eventId, staleAfter.toMillis());
        } catch (RuntimeException error) {
            block(eventId, error);
            throw rejectForRecovery(eventId);
        }
        if (availability < 0) {
            block(eventId, null);
            throw rejectForRecovery(eventId);
        }
        return availability;
    }

    // DB 커밋이 끝난 뒤 완료를 반영하며 Redis 실패로 이미 발급한 쿠폰을 실패로 바꾸지 않는다.
    public boolean complete(CouponReservation reservation) {
        try {
            return sequences.complete(reservation.eventId(), reservation.sequence(), reservation.id());
        } catch (RuntimeException error) {
            block(reservation.eventId(), error);
            return false;
        }
    }

    // DB 커밋이 끝난 묶음의 예약을 이벤트마다 한 번 완료하며 Redis 실패로 발급 성공을 바꾸지 않는다.
    public Set<Long> completeAll(List<CouponReservation> reservations) {
        Map<Long, List<CouponReservation>> byEvent = new LinkedHashMap<>();
        reservations.forEach(reservation -> byEvent.computeIfAbsent(reservation.eventId(), key -> new ArrayList<>())
                .add(reservation));
        Set<Long> soldOut = new HashSet<>();
        byEvent.forEach((eventId, pending) -> {
            try {
                if (sequences.completeAll(eventId, pending.stream().map(CouponReservation::sequence).toList(),
                        pending.stream().map(CouponReservation::id).toList())) {
                    soldOut.add(eventId);
                }
            } catch (RuntimeException error) {
                block(eventId, error);
            }
        });
        return soldOut;
    }

    private void blockAll(Set<Long> eventIds, Throwable cause) {
        for (long eventId : eventIds) {
            if (blockedEvents.add(eventId)) {
                log.warn("event=COUPON_ISSUANCE_BLOCK_DONE couponEventId={} exceptionType={} reason={} requestTraceIds={}",
                        eventId, cause == null ? "NONE" : cause.getClass().getSimpleName(),
                        cause == null ? "STATE_UNAVAILABLE" : CouponFailureDetails.reason(cause),
                        CouponLogContext.requestTraceIds());
            }
        }
        try {
            sequences.blockAll(eventIds);
        } catch (RuntimeException error) {
            log.warn("event=COUPON_REDIS_BLOCK_FAILED couponEventIds={} exceptionType={} reason={} requestTraceIds={}",
                    eventIds.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    error.getClass().getSimpleName(), CouponFailureDetails.reason(error), CouponLogContext.requestTraceIds());
        }
    }

    /*
     * 이벤트를 이 서버의 메모리와 Redis 큐 양쪽에서 차단한다.
     * 처음 차단할 때만 경고 로그를 남기고 Redis 차단이 실패해도 메모리 차단은 유지한다.
     */
    public void block(long eventId, Throwable cause) {
        if (blockedEvents.add(eventId)) {
            log.warn("event=COUPON_ISSUANCE_BLOCK_DONE couponEventId={} exceptionType={} reason={} requestTraceIds={}",
                    eventId, cause == null ? "NONE" : cause.getClass().getSimpleName(),
                    cause == null ? "STATE_UNAVAILABLE" : CouponFailureDetails.reason(cause),
                    CouponLogContext.requestTraceIds());
        }
        try {
            sequences.block(eventId);
        } catch (RuntimeException error) {
            log.warn("event=COUPON_REDIS_BLOCK_FAILED couponEventId={} exceptionType={} reason={} requestTraceIds={}",
                    eventId, error.getClass().getSimpleName(), CouponFailureDetails.reason(error),
                    CouponLogContext.requestTraceIds());
        }
    }

    // 이 서버가 차단한 이벤트면 true를 돌려주며 호출자는 그때 큐 복구를 시도한다.
    public boolean needsRecovery(long eventId) {
        return blockedEvents.contains(eventId);
    }

    // 복구가 끝난 이벤트의 메모리 차단을 푼다.
    public void resume(long eventId) {
        blockedEvents.remove(eventId);
    }

    // 트랜잭션 안에서 발생한 오류는 롤백이 확정된 뒤에만 발급 재접수를 허용한다.
    public CouponException rejectForRecovery(long eventId) {
        CouponException error = CouponException.recoveryRequired(eventId);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        error.confirmIssuanceNotCommitted();
                    }
                }
            });
        } else {
            error.confirmIssuanceNotCommitted();
        }
        return error;
    }

    // 예약한 순번을 큐에 돌려놓으며 실패하면 큐 상태를 알 수 없으므로 이벤트를 차단한다.
    private void release(CouponReservation reservation) {
        try {
            sequences.release(reservation.eventId(), reservation.sequence(), reservation.id());
        } catch (RuntimeException error) {
            block(reservation.eventId(), error);
        }
    }
}
