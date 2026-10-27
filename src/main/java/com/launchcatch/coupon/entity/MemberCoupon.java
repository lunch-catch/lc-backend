package com.launchcatch.coupon.entity;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "member_coupon")
@AttributeOverride(name = "id", column = @Column(name = "member_coupon_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon extends BaseTimeEntity {

    public static final Duration QR_VALIDITY = Duration.ofSeconds(60);

    @Column(name = "coupon_event_id", nullable = false, updatable = false)
    private Long couponEventId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "coupon_code", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID couponCode;

    @Column(name = "qr_version", nullable = false)
    private int qrVersion;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "qr_token", columnDefinition = "BINARY(16)")
    private UUID qrToken;

    @Column(name = "qr_expires_at")
    private LocalDateTime qrExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberCouponStatus status;

    @Column(name = "usable_start_at", nullable = false, updatable = false)
    private LocalDateTime usableStartAt;

    @Column(name = "usable_end_at", nullable = false, updatable = false)
    private LocalDateTime usableEndAt;

    private MemberCoupon(Long memberId, Long eventId, UUID couponCode,
            LocalDateTime usableStartAt, LocalDateTime usableEndAt) {
        if (memberId == null || memberId <= 0 || eventId == null || eventId <= 0) {
            throw new IllegalArgumentException("회원과 이벤트 ID는 양수여야 한다.");
        }
        Objects.requireNonNull(couponCode);
        Objects.requireNonNull(usableStartAt);
        Objects.requireNonNull(usableEndAt);
        if (!usableEndAt.isAfter(usableStartAt)) {
            throw new IllegalArgumentException("쿠폰 사용 종료 시각은 시작 시각보다 늦어야 한다.");
        }
        this.memberId = memberId;
        this.couponEventId = eventId;
        this.couponCode = couponCode;
        this.usableStartAt = usableStartAt;
        this.usableEndAt = usableEndAt;
        this.status = MemberCouponStatus.ISSUED;
    }

    public static MemberCoupon issue(Long memberId, Long eventId, UUID couponCode,
            LocalDateTime usableStartAt, LocalDateTime usableEndAt) {
        return new MemberCoupon(memberId, eventId, couponCode, usableStartAt, usableEndAt);
    }

    /*
     * 사용 가능한 미사용 쿠폰의 QR을 교체하고 버전과 60초 만료 시각을 함께 변경한다.
     * 호출자는 쿠폰 행 잠금을 확보하여 갱신과 삭제, 사용 확정을 직렬화한다.
     */
    public void renewQr(UUID token, LocalDateTime now) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(now);
        if (status == MemberCouponStatus.USED) {
            throw new CouponException(CouponErrorCode.COUPON_ALREADY_USED);
        }
        if (status != MemberCouponStatus.ISSUED
                || now.isBefore(usableStartAt) || !now.isBefore(usableEndAt)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
        qrToken = token;
        qrVersion++;
        qrExpiresAt = now.plus(QR_VALIDITY);
    }

    // 호출자는 행 잠금 아래 최신 QR과 사용 시간을 확인하고 마지막 토큰을 보존한다.
    public void redeem(UUID token, int version, LocalDateTime now) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(now);
        if (status == MemberCouponStatus.USED) {
            validateQrToken(token, version);
            throw new CouponException(CouponErrorCode.COUPON_ALREADY_USED);
        }
        if (status != MemberCouponStatus.ISSUED
                || now.isBefore(usableStartAt) || !now.isBefore(usableEndAt)) {
            throw new CouponException(CouponErrorCode.COUPON_NOT_AVAILABLE);
        }
        validateQrToken(token, version);
        if (qrExpiresAt == null
                || now.isBefore(qrExpiresAt.minus(QR_VALIDITY)) || !now.isBefore(qrExpiresAt)) {
            throw new CouponException(CouponErrorCode.QR_NOT_VALID);
        }
        status = MemberCouponStatus.USED;
    }

    public void validateQrToken(UUID token, int version) {
        if (!Objects.equals(token, qrToken) || version != qrVersion) {
            throw new CouponException(CouponErrorCode.QR_NOT_VALID);
        }
    }

    /**
     * 미사용 쿠폰의 사용 종료 시각에 도달했으면 만료 상태로 변경한다.
     *
     * @param now 한국 시간 기준 현재 시각
     */
    public void changeToExpired(LocalDateTime now) {
        if (status == MemberCouponStatus.ISSUED && !now.isBefore(usableEndAt)) {
            status = MemberCouponStatus.EXPIRED;
        }
    }

    /**
     * 미사용 쿠폰을 삭제 상태로 바꾸고 QR을 무효화한다.
     * 사용 완료 쿠폰은 거절하며 이미 삭제된 쿠폰은 추가로 변경하지 않는다.
     *
     * @return 이번 호출에서 처음 삭제했으면 true이며 이미 삭제된 쿠폰이면 false
     */
    public boolean remove() {
        if (status == MemberCouponStatus.USED) {
            throw new CouponException(CouponErrorCode.COUPON_ALREADY_USED);
        }
        if (status == MemberCouponStatus.REMOVED) {
            return false;
        }
        status = MemberCouponStatus.REMOVED;
        qrVersion++;
        qrToken = null;
        qrExpiresAt = null;
        return true;
    }
}
