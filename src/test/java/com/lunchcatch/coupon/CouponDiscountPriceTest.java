package com.lunchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.coupon.entity.DiscountType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CouponDiscountPriceTest {

    @ParameterizedTest
    @CsvSource({"4500,PERCENT,15,3820", "4500,AMOUNT,675,3820", "4500,AMOUNT,5000,0", "4505,PERCENT,100,0",
            "4500,AMOUNT,3005,1490", "0,AMOUNT,1,0", "4504,AMOUNT,4503,4", "4506,AMOUNT,4505,0"})
    void 할인액을_십원_단위로_반올림하고_가격을_넘지_않게_한다(long price, DiscountType type, int value, long expected) {
        assertThat(CouponDiscountPrice.calculate(price, type, value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"4504,AMOUNT,4504", "4503,AMOUNT,4504", "4504,PERCENT,100", "1,AMOUNT,1", "1,PERCENT,100"})
    void 반올림_전_할인액이_메뉴_가격_이상이면_할인_후_가격은_0원이다(long price, DiscountType type, int value) {
        assertThat(CouponDiscountPrice.calculate(price, type, value)).isZero();
    }

    @Test
    void 유효하지_않은_할인은_거절한다() {
        assertThatThrownBy(() -> CouponDiscountPrice.calculate(4500, DiscountType.PERCENT, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
