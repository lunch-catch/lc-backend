package com.lunchcatch.billing.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 승인 결과를 모르는(UNKNOWN) 결제를 다시 조회하기 위한 대기 행이다. 결제당 한 행(UNIQUE(payment_id))이다.
 *
 * 결제를 UNKNOWN 으로 바꾸는 트랜잭션에서 함께 만든다. 따로 만들면 그 사이에 서버가 죽었을 때
 * UNKNOWN 결제가 재조회 대상에서 빠져 점주가 새 충전을 영영 못 한다(BILLING-008).
 *
 * 결제를 객체로 참조하지 않고 ID 로 갖는다. 결제와 생명주기가 다르고, 워커는 이 테이블만 훑는다.
 * 재조회 워커는 대상 행을 선점하고(claim) 확정하지 못하면 시도를 기록한다(recordMiss).
 */
@Entity
@Table(name = "payment_reconciliation_outbox")
@AttributeOverride(name = "id", column = @Column(name = "payment_reconciliation_outbox_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReconciliationOutbox extends BaseTimeEntity {

    private static final int LAST_ERROR_MAX = 500;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_retry", nullable = false, updatable = false)
    private int maxRetry;

    /** PENDING 이면 chk_payment_reconciliation_outbox_next_retry 가 값을 요구한다. */
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "last_error", length = LAST_ERROR_MAX)
    private String lastError;

    private PaymentReconciliationOutbox(Long paymentId, int maxRetry, LocalDateTime nextRetryAt) {
        if (paymentId == null) {
            throw new IllegalArgumentException("결제 ID는 필수입니다.");
        }
        if (maxRetry < 0) {
            throw new IllegalArgumentException("재조회 상한은 0 이상이어야 합니다: " + maxRetry);
        }
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("다음 재조회 시각은 필수입니다.");
        }
        this.paymentId = paymentId;
        this.maxRetry = maxRetry;
        this.nextRetryAt = nextRetryAt;
        this.attemptCount = 0;
        this.status = OutboxStatus.PENDING;
    }

    // 재조회를 기다리는 행을 만든다
    public static PaymentReconciliationOutbox pending(Long paymentId, int maxRetry, LocalDateTime nextRetryAt) {
        return new PaymentReconciliationOutbox(paymentId, maxRetry, nextRetryAt);
    }

    /*
     * 결제 결과가 확정됐다. 이미 RESOLVED 면 아무것도 하지 않는다.
     * FAILED_PERMANENT 에서도 닫을 수 있다. 상한을 넘긴 건을 관리자가 재조회로 확정하는 경로가 있다.
     */
    public void resolve(LocalDateTime checkedAt) {
        if (status == OutboxStatus.RESOLVED) {
            return;
        }
        if (checkedAt == null) {
            throw new IllegalArgumentException("확인 시각은 필수입니다.");
        }
        this.status = OutboxStatus.RESOLVED;
        this.lastCheckedAt = checkedAt;
    }

    /*
     * 워커가 이 행을 선점한다. 다음 재조회 시각을 임시로 미뤄 다른 서버가 같은 행을 집지 않게 한다.
     * 결제대행사 조회는 트랜잭션 밖에서 하므로 행 잠금만으로는 그 구간을 지킬 수 없다.
     * 선점한 서버가 죽어도 이 시각이 지나면 다시 대상이 된다.
     */
    public void claim(LocalDateTime leaseUntil) {
        requirePending();
        if (leaseUntil == null) {
            throw new IllegalArgumentException("선점 만료 시각은 필수입니다.");
        }
        this.nextRetryAt = leaseUntil;
    }

    /*
     * 재조회를 했지만 결과를 확정하지 못했다. 시도 횟수를 늘리고 다음 재조회 시각을 정한다.
     * 시도 횟수가 상한에 닿으면 FAILED_PERMANENT 로 두고 다음 시각을 비운다. 결제는 UNKNOWN 으로 남고 관리자가 확정한다.
     * 상한에 닿았으면 true 를 돌려준다.
     */
    public boolean recordMiss(LocalDateTime checkedAt, String error, LocalDateTime nextRetryAt) {
        requirePending();
        if (checkedAt == null) {
            throw new IllegalArgumentException("확인 시각은 필수입니다.");
        }
        this.attemptCount++;
        this.lastCheckedAt = checkedAt;
        this.lastError = shorten(error);
        if (attemptCount >= maxRetry) {
            this.status = OutboxStatus.FAILED_PERMANENT;
            this.nextRetryAt = null;
            return true;
        }
        if (nextRetryAt == null) {
            throw new IllegalArgumentException("다음 재조회 시각은 필수입니다.");
        }
        this.nextRetryAt = nextRetryAt;
        return false;
    }

    /*
     * 관리자가 재조회했지만 확정하지 못했다. 시도 횟수와 상태는 그대로 두고 확인 시각과 사유만 남긴다.
     * 이미 닫힌 행은 건드리지 않는다.
     */
    public void touch(LocalDateTime checkedAt, String error) {
        if (status == OutboxStatus.RESOLVED) {
            return;
        }
        if (checkedAt == null) {
            throw new IllegalArgumentException("확인 시각은 필수입니다.");
        }
        this.lastCheckedAt = checkedAt;
        this.lastError = shorten(error);
    }

    private void requirePending() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException("재조회를 기다리는 행이 아닙니다: " + status);
        }
    }

    private static String shorten(String error) {
        if (error == null || error.length() <= LAST_ERROR_MAX) {
            return error;
        }
        return error.substring(0, LAST_ERROR_MAX);
    }
}
