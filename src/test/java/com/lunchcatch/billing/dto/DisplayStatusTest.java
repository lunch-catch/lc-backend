package com.lunchcatch.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.billing.entity.PaymentStatus;
import org.junit.jupiter.api.Test;

class DisplayStatusTest {

    @Test
    void 점주_화면에는_확인_중인_결제를_결과_불명이_아니라_확인_중으로_보여_준다() {
        assertThat(DisplayStatus.of(PaymentStatus.UNKNOWN)).isEqualTo(DisplayStatus.CONFIRMING);
    }

    @Test
    void 나머지_상태는_화면_상태로_하나씩_옮긴다() {
        assertThat(DisplayStatus.of(PaymentStatus.REQUESTED)).isEqualTo(DisplayStatus.IN_PROGRESS);
        assertThat(DisplayStatus.of(PaymentStatus.APPROVED)).isEqualTo(DisplayStatus.COMPLETED);
        assertThat(DisplayStatus.of(PaymentStatus.FAILED)).isEqualTo(DisplayStatus.FAILED);
        assertThat(DisplayStatus.of(PaymentStatus.CANCELLED)).isEqualTo(DisplayStatus.CANCELLED);
    }
}
