package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.coupon.entity.DiscountType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CouponDiscountPriceTest {

    @ParameterizedTest
    @CsvSource({"4500,PERCENT,15,3820", "4500,AMOUNT,675,3820", "4500,AMOUNT,5000,0", "4505,PERCENT,100,0",
            "4500,AMOUNT,3005,1490", "0,AMOUNT,1,0"})
    void 할인액을_십원_단위로_반올림하고_가격을_넘지_않게_한다(long price, DiscountType type, int value, long expected) {
        assertThat(CouponDiscountPrice.calculate(price, type, value)).isEqualTo(expected);
    }

    @Test
    void 유효하지_않은_할인은_거절한다() {
        assertThatThrownBy(() -> CouponDiscountPrice.calculate(4500, DiscountType.PERCENT, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
