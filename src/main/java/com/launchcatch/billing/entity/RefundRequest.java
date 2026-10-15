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
 * 점주의 환불 요청이다. 금액은 요청 시점 잔액 전액이고 부분 환불은 없다.
 *
 * 요청만으로는 원장이 바뀌지 않는다. 승인할 때 원장에 REFUND 를 기록하고 COMPLETED 로 바꾼다.
 * 수수료는 이 프로젝트에서 0 이라 항상 0 을 저장한다. payment_id 는 쓰지 않는다. 잔액 전액은 여러 결제의 합이라
 * 결제 하나에 묶이지 않는다.
 * 점주마다 대기 요청은 하나뿐이고, DB 의 생성 컬럼 pending_dedup_key 와 그 UNIQUE 가 막는다.
 */
@Entity
@Table(name = "refund_request")
@AttributeOverride(name = "id", column = @Column(name = "refund_request_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest extends BaseTimeEntity {

    private static final int REASON_MAX = 255;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(updatable = false)
    private Long fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    /** 점주가 적은 요청 사유다. 선택이다. */
    @Column(length = REASON_MAX, updatable = false)
    private String reason;

    /** 반려 사유다. 점주가 목록에서 직접 본다. REJECTED 일 때만 값이 있다(chk_refund_request_reject_reason). */
    @Column(name = "reject_reason", length = REASON_MAX)
    private String rejectReason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 처리한 관리자 ID 다. REQUESTED 가 아니면 필수다(chk_refund_request_processed). */
    @Column(name = "processed_by")
    private Long processedBy;

    private RefundRequest(Long ownerId, long amount, String reason) {
        if (ownerId == null) {
            throw new IllegalArgumentException("점주 ID는 필수입니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("환불 금액은 0보다 커야 합니다: " + amount);
        }
        if (reason != null && reason.length() > REASON_MAX) {
            throw new IllegalArgumentException("요청 사유는 " + REASON_MAX + "자 이하여야 합니다.");
        }
        this.ownerId = ownerId;
        this.amount = amount;
        this.fee = 0L;
        this.status = RefundStatus.REQUESTED;
        this.reason = reason;
    }

    // 잔액 전액을 환불해 달라는 요청을 만든다
    public static RefundRequest requestAll(Long ownerId, long balance, String reason) {
        return new RefundRequest(ownerId, balance, reason);
    }

    // 승인해 원장 기록을 마친 요청을 완료로 닫는다
    public void complete(Long adminId, LocalDateTime processedAt) {
        close(RefundStatus.COMPLETED, adminId, processedAt);
    }

    // 반려하며 사유는 점주에게 보이는 값이라 비울 수 없다
    public void reject(Long adminId, String rejectReason, LocalDateTime processedAt) {
        if (rejectReason == null || rejectReason.isBlank() || rejectReason.length() > REASON_MAX) {
            throw new IllegalArgumentException("반려 사유는 1자 이상 " + REASON_MAX + "자 이하여야 합니다.");
        }
        close(RefundStatus.REJECTED, adminId, processedAt);
        this.rejectReason = rejectReason;
    }

    private void close(RefundStatus next, Long adminId, LocalDateTime processedAt) {
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("처리를 기다리는 요청만 닫을 수 있습니다: " + status);
        }
        if (adminId == null || processedAt == null) {
            throw new IllegalArgumentException("처리한 관리자와 시각은 필수입니다.");
        }
        this.status = next;
        this.processedBy = adminId;
        this.processedAt = processedAt;
    }
}
