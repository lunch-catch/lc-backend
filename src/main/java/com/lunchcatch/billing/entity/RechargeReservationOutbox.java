package com.lunchcatch.billing.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 충전 직후 재예약(I-03)을 기다리는 행이다. 결제당 한 행(UNIQUE(payment_id))이다.
 *
 * 새 CHARGE 를 만든 승인 트랜잭션에서 함께 만든다. 재예약을 커밋 뒤 리스너가 새 트랜잭션으로 열면
 * 승인의 커넥션을 쥔 채 커넥션을 하나 더 달라고 해서, 풀이 차면 승인끼리 서로를 기다린다(#1100).
 * 이 행으로 넘기면 승인은 커넥션 하나만 쓰고, 재예약이 실패해도 워커가 다시 시도한다.
 *
 * 충전한 영업일(businessDate)이 지나면 재예약하지 않고 닫는다. 그날 캠페인을 다시 켤 기회는 지났고,
 * 00:00 예약이 그 잔액을 새 날 예산으로 배분한다. 그래서 시도 횟수에 상한을 두지 않는다.
 */
@Entity
@Table(name = "recharge_reservation_outbox")
@AttributeOverride(name = "id", column = @Column(name = "recharge_reservation_outbox_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RechargeReservationOutbox extends BaseTimeEntity {

    private static final int LAST_ERROR_MAX = 500;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RechargeReservationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    /** PENDING 이면 chk_recharge_reservation_outbox_next_retry 가 값을 요구한다. */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = LAST_ERROR_MAX)
    private String lastError;

    private RechargeReservationOutbox(Long paymentId, LocalDate businessDate, LocalDateTime nextRetryAt) {
        if (paymentId == null) {
            throw new IllegalArgumentException("결제 ID는 필수입니다.");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("충전한 영업일은 필수입니다.");
        }
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("재예약 시각은 필수입니다.");
        }
        this.paymentId = paymentId;
        this.businessDate = businessDate;
        this.nextRetryAt = nextRetryAt;
        this.attemptCount = 0;
        this.status = RechargeReservationStatus.PENDING;
    }

    // 재예약을 기다리는 행을 만든다
    public static RechargeReservationOutbox pending(Long paymentId, LocalDate businessDate, LocalDateTime nextRetryAt) {
        return new RechargeReservationOutbox(paymentId, businessDate, nextRetryAt);
    }

    /*
     * 워커가 이 행을 선점한다. 다음 시각을 임시로 미뤄 다른 서버가 같은 행을 집지 않게 한다.
     * 재예약은 선점 트랜잭션 밖에서 하므로 행 잠금만으로는 그 구간을 지킬 수 없다.
     * 선점한 서버가 죽어도 이 시각이 지나면 다시 대상이 된다.
     */
    public void claim(LocalDateTime leaseUntil) {
        requirePending();
        if (leaseUntil == null) {
            throw new IllegalArgumentException("선점 만료 시각은 필수입니다.");
        }
        this.nextRetryAt = leaseUntil;
    }

    // 재예약을 마쳤다. 이미 닫힌 행은 건드리지 않는다
    public void complete() {
        if (status != RechargeReservationStatus.PENDING) {
            return;
        }
        this.status = RechargeReservationStatus.DONE;
        this.nextRetryAt = null;
    }

    // 충전한 영업일이 지나 재예약하지 않고 닫는다. 이미 닫힌 행은 건드리지 않는다
    public void expire() {
        if (status != RechargeReservationStatus.PENDING) {
            return;
        }
        this.status = RechargeReservationStatus.EXPIRED;
        this.nextRetryAt = null;
    }

    // 재예약이 실패했다. 시도 횟수를 늘리고 다음 시각을 정한다
    public void recordFailure(String error, LocalDateTime nextRetryAt) {
        requirePending();
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("다음 재예약 시각은 필수입니다.");
        }
        this.attemptCount++;
        this.lastError = shorten(error);
        this.nextRetryAt = nextRetryAt;
    }

    private void requirePending() {
        if (status != RechargeReservationStatus.PENDING) {
            throw new IllegalStateException("재예약을 기다리는 행이 아닙니다: " + status);
        }
    }

    private static String shorten(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX);
    }
}
