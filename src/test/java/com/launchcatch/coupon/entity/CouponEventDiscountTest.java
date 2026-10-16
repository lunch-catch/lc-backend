package com.launchcatch.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class CouponEventDiscountTest {

    @ParameterizedTest
    @CsvSource({"10000,680", "680,680", "300,300"})
    void 메뉴는_단건_스냅샷의_차액을_다시_반올림하지_않고_주문금액으로_제한한다(int orderAmount, int expected) {
        assertThat(event(DiscountTargetType.MENU, DiscountType.PERCENT, 15, 4500L, 3820L).calculateDiscount(orderAmount))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "3000,10000,3000", "3000,3000,3000", "3000,1005,1005",
            "674,10000,670", "675,10000,680", "675,677,677",
            "1004,1003,1003", "2147483646,2147483647,2147483647"
    })
    void 전체_정액은_무료_조건과_10원_반올림_후_주문금액_상한을_적용한다(int discount, int orderAmount, int expected) {
        assertThat(event(DiscountTargetType.ALL, DiscountType.AMOUNT, discount, null, null).calculateDiscount(orderAmount))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "10000,15,1500", "4500,15,680", "30,15,0", "100,5,10", "6,99,6",
            "1,1,0", "1,100,1", "1005,100,1005", "2147483647,99,2126008810", "2147483647,100,2147483647"
    })
    void 전체_정률은_주문금액과_할인율로_계산하고_한_번만_반올림한다(int orderAmount, int percent, int expected) {
        assertThat(event(DiscountTargetType.ALL, DiscountType.PERCENT, percent, null, null).calculateDiscount(orderAmount))
                .isEqualTo(expected);
    }

    @Test
    void BIGINT_메뉴의_차액도_주문금액_상한을_적용한_뒤_INT로_반환한다() {
        assertThat(event(DiscountTargetType.MENU, DiscountType.PERCENT, 100, 5_000_000_000L, 0L).calculateDiscount(Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }

    private CouponEvent event(DiscountTargetType target, DiscountType type, int value, Long price, Long discountedPrice) {
        CouponEvent event = new CouponEvent();
        ReflectionTestUtils.setField(event, "discountTargetType", target);
        ReflectionTestUtils.setField(event, "discountType", type);
        ReflectionTestUtils.setField(event, "discountValue", value);
        ReflectionTestUtils.setField(event, "targetMenuPrice", price);
        ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", discountedPrice);
        return event;
    }
}
