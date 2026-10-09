package com.launchcatch.coupon.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "coupon_inventory")
@AttributeOverride(name = "id", column = @Column(name = "coupon_inventory_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponInventory extends BaseTimeEntity {

    @Column(name = "coupon_event_id", nullable = false, updatable = false)
    private Long couponEventId;

    @Column(name = "sequence_no", nullable = false, updatable = false)
    private int sequenceNo;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "coupon_code", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID couponCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CouponInventoryStatus status;

    @Column(name = "usable_start_at", nullable = false, updatable = false)
    private LocalDateTime usableStartAt;

    @Column(name = "usable_end_at", nullable = false, updatable = false)
    private LocalDateTime usableEndAt;
}
