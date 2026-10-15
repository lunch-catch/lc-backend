package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 포인트 원장 한 줄이다. 추가만 하고 고치거나 지우지 않는다.
 *
 * 잔액은 컬럼이나 캐시로 두지 않고 이 테이블의 합으로 계산한다. 계산식은
 * SUM(amount) 에서 DEDUCT 를 뺀 것이다. DEDUCT 를 빼는 이유는 그 금액이 예약 단계에서
 * 이미 잔액에서 빠졌기 때문이다.
 *
 * 부호는 저장값이 갖는다. BR-013의 "CHARGE - RESERVE + RELEASE - REFUND
 * + ADJUST" 는 크기로 적은 표기다. 그 식을 부호가 있는 저장값에 그대로 옮겨 빼면 부호가
 * 뒤집힌다. 그래서 아래 팩터리는 모두 양수를 받고 종류에 맞는 부호를 안에서 정한다.
 * 조정(ADJUST)만 예외다. 사람이 늘리거나 줄이는 방향을 정하므로 부호가 있는 값을 그대로 받는다.
 *
 * 1원이 1포인트다. 금액 컬럼을 따로 두지 않는다.
 *
 * 이 판은 CHARGE, ADJUST 기록과 조회에 필요한 컬럼만 매핑한다. refund_request_id 는
 * 그것을 쓰는 작업과 함께 더한다.
 * ddl-auto 가 validate 라서 매핑하지 않은 컬럼은 기동을 막지 않는다. 그 컬럼은 전부
 * NULL 을 허용하므로 CHARGE 기록에도 영향이 없다.
 */
@Entity
@Table(name = "point_ledger")
@AttributeOverride(name = "id", column = @Column(name = "point_ledger_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointLedger extends BaseTimeEntity {

    private static final int REASON_MAX = 255;
    private static final int IDEMPOTENCY_KEY_MAX = 64;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    /** CHARGE 는 항상 값이 있다. 충전 직후 재예약이 만드는 RESERVE 도 값을 가진다. */
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    /*
     * RESERVE, RELEASE, DEDUCT, REFUND 의 캠페인별 내역이다. CHARGE 는 NULL 이다.
     * ADJUST 는 어느 캠페인의 정정인지 남길 때만 값이 있고, 정산 안의 캠페인별 계산(해제, 대사, 진행률, 소진 요약)에는 쓰지 않는다.
     */
    @Column(name = "campaign_id", updatable = false)
    private Long campaignId;

    /** RESERVE 와 RELEASE 만 값이 있다. 그 예약이 속한 영업일이다. */
    @Column(name = "business_date", updatable = false)
    private LocalDate businessDate;

    /** DEDUCT 만 값이 있다. 광고 서빙이 발급한 UUID 이며 BINARY(16) 로 저장한다. */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "serve_id", updatable = false)
    private UUID serveId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20, updatable = false)
    private EntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LedgerSource source;

    /** ADJUST 의 사유다. 다른 종류는 NULL 이다. */
    @Column(length = REASON_MAX, updatable = false)
    private String reason;

    /** ADJUST 의 멱등 키다. 관리자 화면이 보낸 requestId 이고 (owner_id, idempotency_key) 가 UNIQUE 다. */
    @Column(name = "idempotency_key", length = IDEMPOTENCY_KEY_MAX, updatable = false)
    private String idempotencyKey;

    /** 부호가 있는 변동량이다. chk_point_ledger_amount_sign 이 종류별 부호를 강제한다. */
    @Column(nullable = false, updatable = false)
    private long amount;

    private PointLedger(Long ownerId, Long paymentId, EntryType entryType,
                        LedgerSource source, long amount) {
        if (ownerId == null) {
            throw new IllegalArgumentException("점주 ID는 필수입니다.");
        }
        this.ownerId = ownerId;
        this.paymentId = paymentId;
        this.entryType = entryType;
        this.source = source;
        this.amount = amount;
    }

    /*
     * 충전을 기록한다. 금액은 양수로 받고 양수로 저장한다.
     *
     * (payment_id, entry_type) 이 UNIQUE 라 같은 결제의 충전은 한 줄뿐이다. 승인 확정과
     * 웹훅, 재조회 워커가 같은 결제를 동시에 확정하려 해도 한 줄만 남는다. 그 유일 제약
     * 위반이 "이미 충전됐다" 를 뜻한다.
     *
     * source 를 받는 이유는 기록하는 경로가 둘이기 때문이다. 승인 확정은 REALTIME 이고,
     * 대사가 빠진 충전을 채우는 보정은 RECONCILIATION 이다. 사람이 넣는 경로는 없다.
     */
    public static PointLedger charge(Long ownerId, Long paymentId, long amount, LedgerSource source) {
        if (paymentId == null) {
            throw new IllegalArgumentException("충전은 결제 ID가 필수입니다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다: " + amount);
        }
        if (source != LedgerSource.REALTIME && source != LedgerSource.RECONCILIATION) {
            throw new IllegalArgumentException("충전의 기록 주체는 REALTIME 이나 RECONCILIATION 입니다: " + source);
        }
        return new PointLedger(ownerId, paymentId, EntryType.CHARGE, source, amount);
    }

    /*
     * 관리자 수동 조정을 기록한다. 금액은 부호를 가진 값이고 0 이 아니어야 한다.
     *
     * 양수는 사후에 확인된 과오 차감을 돌려주는 정정이고 소진액 집계에서 빠진다. 음수는 잔액을 줄인다.
     * 기록 주체는 사람뿐이라 source 는 MANUAL 로 고정한다(chk_point_ledger_adjust_source).
     * 같은 점주의 같은 멱등 키는 한 줄뿐이다(uk_point_ledger_adjust_idem).
     */
    public static PointLedger adjust(Long ownerId, long amount, String reason, Long campaignId,
                                     String idempotencyKey) {
        if (amount == 0) {
            throw new IllegalArgumentException("조정 금액은 0일 수 없습니다.");
        }
        if (reason == null || reason.isBlank() || reason.length() > REASON_MAX) {
            throw new IllegalArgumentException("조정 사유는 1자 이상 " + REASON_MAX + "자 이하여야 합니다.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > IDEMPOTENCY_KEY_MAX) {
            throw new IllegalArgumentException("멱등 키는 1자 이상 " + IDEMPOTENCY_KEY_MAX + "자 이하여야 합니다.");
        }
        PointLedger ledger = new PointLedger(ownerId, null, EntryType.ADJUST, LedgerSource.MANUAL, amount);
        ledger.reason = reason;
        ledger.campaignId = campaignId;
        ledger.idempotencyKey = idempotencyKey;
        return ledger;
    }
}
