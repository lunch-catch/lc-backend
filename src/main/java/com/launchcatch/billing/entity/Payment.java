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
 * 결제(충전) 한 건이다.
 *
 * 주문을 먼저 만들고 결제창을 연다. 금액을 이 행에 먼저 저장해 두고 승인 확정 때 이 값으로
 * 위변조를 검증한다. 클라이언트가 보낸 금액을 믿지 않는다.
 *
 * 상태마다 어느 컬럼이 채워져야 하는지를 DB CHECK 다섯 개가 강제한다. 예를 들어 APPROVED 는
 * approved_at 이 있어야 하고, FAILED 는 failure_reason 이 있어야 하며, APPROVED 와 FAILED 와
 * UNKNOWN 은 pg_tid 가 있어야 한다. 그래서 상태 전이를 setter 로 열지 않고 아래 메서드로만
 * 한다. 한쪽만 바꾸면 저장이 거부되는데 그 순간은 결제 결과를 기록하려는 때다.
 */
@Entity
@Table(name = "payment")
@AttributeOverride(name = "id", column = @Column(name = "payment_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    private static final int ORDER_ID_MAX = 64;
    private static final int PG_TID_MAX = 100;
    private static final int FAILURE_REASON_MAX = 255;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    /** 우리 서버가 발급한 주문번호다. 결제대행사에 보내는 식별자이고 UNIQUE 다. */
    @Column(name = "order_id", nullable = false, length = ORDER_ID_MAX, updatable = false)
    private String orderId;

    /*
     * 결제대행사의 결제 식별자다. 토스의 paymentKey 가 이 자리에 온다.
     * 주문 생성 시점에는 없고 승인을 시도하기 직전에 채워 커밋한다.
     */
    @Column(name = "pg_tid", length = PG_TID_MAX)
    private String pgTid;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
    private String failureReason;

    /** 승인 뒤 결제대행사 쪽에서 취소된 경우에만 값이 있다. */
    @Column(name = "pg_cancel_id", length = PG_TID_MAX)
    private String pgCancelId;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    private Payment(Long ownerId, String orderId, long amount) {
        if (ownerId == null) {
            throw new IllegalArgumentException("점주 ID는 필수입니다.");
        }
        if (orderId == null || orderId.isBlank() || orderId.length() > ORDER_ID_MAX) {
            throw new IllegalArgumentException("주문번호는 필수이며, " + ORDER_ID_MAX + "자 이하여야 합니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다: " + amount);
        }

        this.ownerId = ownerId;
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.REQUESTED;
    }

    /** 결제창을 열기 전에 만드는 주문이다. 상태는 항상 REQUESTED 로 시작한다. */
    public static Payment order(Long ownerId, String orderId, long amount) {
        return new Payment(ownerId, orderId, amount);
    }

    /*
     * 승인을 시도하기 직전에 결제 식별자를 적는다. 이 값만 담은 짧은 트랜잭션으로 커밋한다.
     *
     * 먼저 커밋하는 이유는 두 가지다. 응답을 못 받아 UNKNOWN 이 될 때 이 값이 없으면
     * chk_payment_pg_tid 가 저장을 거부하고, 재조회로 결과를 확정할 단서도 사라진다.
     *
     * 같은 값을 다시 적는 것은 허용한다. 같은 주문으로 승인을 다시 시도하는 경로가 있다.
     */
    public void assignPgTid(String pgTid) {
        if (pgTid == null || pgTid.isBlank() || pgTid.length() > PG_TID_MAX) {
            throw new IllegalArgumentException("결제 식별자는 필수이며, " + PG_TID_MAX + "자 이하여야 합니다.");
        }
        if (pgTid.equals(this.pgTid)) {
            return;
        }
        if (status != PaymentStatus.REQUESTED) {
            throw new IllegalStateException("승인을 기다리는 주문에만 결제 식별자를 적을 수 있습니다: " + status);
        }
        this.pgTid = pgTid;
    }

    /*
     * 승인으로 확정한다. 이미 APPROVED 면 아무것도 하지 않는다.
     * 승인 확정은 승인 확정과 웹훅, 재조회 워커, 관리자 재조회가 함께 쓰는 경로라 멱등해야 한다.
     */
    public void approve(LocalDateTime approvedAt) {
        if (status == PaymentStatus.APPROVED) {
            return;
        }
        if (approvedAt == null) {
            throw new IllegalArgumentException("승인 시각은 필수입니다.");
        }
        requirePgTid();
        requireUndetermined();
        this.status = PaymentStatus.APPROVED;
        this.approvedAt = approvedAt;
    }

    /** 결제대행사가 명확히 거절한 경우다. 사유는 DB CHECK 가 필수로 요구한다. */
    public void fail(String failureReason) {
        if (status == PaymentStatus.FAILED) {
            return;
        }
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("실패 사유는 필수입니다.");
        }
        requirePgTid();
        requireUndetermined();
        this.status = PaymentStatus.FAILED;
        this.failureReason = shorten(failureReason);
    }

    /*
     * 승인 결과를 알 수 없는 상태로 둔다.
     * 실패로 단정하지 않는 자리다. 재조회가 승인이나 실패로 확정한다.
     */
    public void markUnknown() {
        if (status == PaymentStatus.UNKNOWN) {
            return;
        }
        requirePgTid();
        requireUndetermined();
        this.status = PaymentStatus.UNKNOWN;
    }

    /*
     * 승인 전에 취소나 만료로 확인된 주문이다. 점주는 새 주문을 만들어야 한다.
     *
     * 승인된 결제가 결제대행사 쪽에서 취소된 경우는 여기서 전이하지 않는다. 그 건은 충전을
     * 자동으로 되돌리지 않고 대사가 불일치로 기록한 뒤 관리자가 수동 조정으로 처리한다.
     */
    public void cancel(LocalDateTime canceledAt, String pgCancelId) {
        if (status == PaymentStatus.CANCELLED) {
            return;
        }
        if (canceledAt == null) {
            throw new IllegalArgumentException("취소 시각은 필수입니다.");
        }
        requireUndetermined();
        this.status = PaymentStatus.CANCELLED;
        this.canceledAt = canceledAt;
        this.pgCancelId = pgCancelId;
    }

    /** 확정된 결과를 덮어쓰지 못하게 막는다. 서비스가 먼저 거르고 이것이 마지막 방어선이다. */
    private void requireUndetermined() {
        if (status != PaymentStatus.REQUESTED && status != PaymentStatus.UNKNOWN) {
            throw new IllegalStateException("이미 결과가 확정된 결제입니다: " + status);
        }
    }

    private void requirePgTid() {
        if (pgTid == null) {
            throw new IllegalStateException("결제 식별자가 없어 결과를 기록할 수 없습니다.");
        }
    }

    /*
     * 500자 컬럼이 아니라 255자 컬럼이다.
     * 넘치면 결과 기록 자체가 사라지므로 잘라 넣는다.
     */
    private String shorten(String reason) {
        return reason.length() <= FAILURE_REASON_MAX ? reason : reason.substring(0, FAILURE_REASON_MAX);
    }
}
