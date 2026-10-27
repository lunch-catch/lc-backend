package com.lunchcatch.coupon;

import com.lunchcatch.coupon.entity.DiscountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class CouponDiscountPrice {

    public static final int ROUNDING_UNIT = 10;

    private CouponDiscountPrice() { }

    public static long calculate(long original, DiscountType type, int value) {
        Objects.requireNonNull(type);
        if (original < 0 || value <= 0 || (type == DiscountType.PERCENT && value > 100)) {
            throw new IllegalArgumentException("가격과 할인값이 유효하지 않다.");
        }
        BigDecimal discount = type == DiscountType.PERCENT
                ? BigDecimal.valueOf(original).multiply(BigDecimal.valueOf(value)).movePointLeft(2)
                : BigDecimal.valueOf(value);
        if (discount.compareTo(BigDecimal.valueOf(original)) >= 0) {
            return 0;
        }
        BigDecimal rounded = discount.divide(BigDecimal.valueOf(ROUNDING_UNIT), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(ROUNDING_UNIT));
        return original - rounded.min(BigDecimal.valueOf(original)).longValueExact();
    }
}
