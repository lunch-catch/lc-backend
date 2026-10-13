package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
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
 * 재조회 시도와 백오프 계산은 재조회 워커 작업이 더한다.
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
}
