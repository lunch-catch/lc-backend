package com.lunchcatch.billing.service;

import com.lunchcatch.billing.config.RechargeReservationRetryProperties;
import com.lunchcatch.billing.entity.RechargeReservationOutbox;
import com.lunchcatch.billing.repository.RechargeReservationOutboxRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 재예약 아웃박스 행을 선점하고 결과를 적는다. 메서드마다 짧은 트랜잭션 하나이고 재예약은 하지 않는다.
 *
 * 선점은 시각이 된 행을 SKIP LOCKED 로 잠그고 다음 시각을 선점 만료 시각으로 미룬 채 커밋한다.
 * 커밋하면 잠금이 풀리지만 시각이 미뤄졌으므로 다른 서버가 같은 행을 다시 집지 않는다.
 * 재예약을 끝내기 전에 서버가 죽어도 만료 시각이 지나면 다시 대상이 된다.
 */
@Service
@RequiredArgsConstructor
public class RechargeReservationOutboxService {

    private final RechargeReservationOutboxRepository outboxRepository;
    private final RechargeReservationRetryProperties properties;
    private final Clock clock;

    // 선점한 행을 오래 기다린 순서로 돌려준다
    @Transactional
    public List<RechargeReservationOutbox> claimDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime leaseUntil = now.plus(properties.claimLease());
        List<RechargeReservationOutbox> due = outboxRepository.findDueForClaim(now, properties.batchSize());
        due.forEach(outbox -> outbox.claim(leaseUntil));
        return due;
    }

    @Transactional
    public void complete(Long outboxId) {
        find(outboxId).complete();
    }

    @Transactional
    public void expire(Long outboxId) {
        find(outboxId).expire();
    }

    // 다음 시각은 이 행이 지금까지 실패한 횟수로 정한다
    @Transactional
    public void recordFailure(Long outboxId, String error) {
        RechargeReservationOutbox outbox = find(outboxId);
        LocalDateTime nextRetryAt = LocalDateTime.now(clock).plus(properties.delayAfter(outbox.getAttemptCount() + 1));
        outbox.recordFailure(error, nextRetryAt);
    }

    private RechargeReservationOutbox find(Long outboxId) {
        return outboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("재예약 아웃박스 행이 없습니다: " + outboxId));
    }
}
