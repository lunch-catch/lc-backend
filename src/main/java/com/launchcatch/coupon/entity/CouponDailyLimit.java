package com.launchcatch.coupon.entity;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
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

    // 호출자는 해당 회원과 영업일의 행 잠금을 확보한 뒤 발급 횟수를 증가시킨다.
    public void increaseIssuedCount(int dailyLimit) {
        if (issuedCount >= dailyLimit) {
            throw new CouponException(CouponErrorCode.DAILY_LIMIT_REACHED);
        }
        issuedCount++;
    }

    // 쿠폰 삭제로 일일 발급 횟수를 1 줄이며 이미 0이면 음수로 내리지 않는다.
    public void decreaseIssuedCount() {
        if (issuedCount > 0) {
            issuedCount--;
        }
    }
}
