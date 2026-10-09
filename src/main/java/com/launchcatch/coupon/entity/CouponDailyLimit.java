package com.launchcatch.coupon.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "coupon_daily_limit")
@AttributeOverride(name = "id", column = @Column(name = "coupon_daily_limit_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponDailyLimit extends BaseTimeEntity {

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "issued_count", nullable = false)
    private int issuedCount;
}
