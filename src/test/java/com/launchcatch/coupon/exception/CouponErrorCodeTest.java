package com.launchcatch.coupon.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CouponErrorCodeTest {
    @ParameterizedTest
    @CsvSource({
            "INVENTORY_EXHAUSTED, true", "ISSUANCE_BUSY, true",
            "COUPON_NOT_FOUND, false", "COUPON_NOT_AVAILABLE, false", "MEMBER_ACCOUNT_NOT_ALLOWED, false",
            "CAMPAIGN_NOT_WISHED, false", "EVENT_ALREADY_ISSUED, false", "DAILY_LIMIT_REACHED, false",
            "LOCATION_NOT_ALLOWED, false", "QR_NOT_VALID, false", "COUPON_ACCESS_DENIED, false",
            "COUPON_ALREADY_USED, false", "OWNER_ACCOUNT_NOT_ALLOWED, false"
    })
    void 소진과_혼잡만_대량의_예상된_트래픽으로_표시한다(CouponErrorCode code, boolean expected) {
        assertThat(code.isExpectedTraffic()).isEqualTo(expected);
    }
}
