package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RefundRequestTest {

    private static final Long OWNER = 7L;
    private static final Long ADMIN = 2L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 11, 0);

    @Test
    void 요청은_잔액_전액과_수수료_0으로_대기_상태에서_시작한다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, "서비스 중단");

        assertThat(request.getOwnerId()).isEqualTo(OWNER);
        assertThat(request.getAmount()).isEqualTo(12000L);
        assertThat(request.getFee()).isZero();
        assertThat(request.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(request.getReason()).isEqualTo("서비스 중단");
        assertThat(request.getProcessedAt()).isNull();
    }

    @Test
    void 금액이_0_이하이거나_점주가_없거나_사유가_255자를_넘으면_만들지_않는다() {
        assertThatThrownBy(() -> RefundRequest.requestAll(OWNER, 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundRequest.requestAll(null, 100L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundRequest.requestAll(OWNER, 100L, "가".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 완료하면_처리자와_시각이_남는다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, null);

        request.complete(ADMIN, NOW);

        assertThat(request.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(request.getProcessedBy()).isEqualTo(ADMIN);
        assertThat(request.getProcessedAt()).isEqualTo(NOW);
        assertThat(request.getRejectReason()).isNull();
    }

    @Test
    void 반려하면_사유와_처리자가_남는다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, null);

        request.reject(ADMIN, "약관상 환불 불가 기간", NOW);

        assertThat(request.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(request.getRejectReason()).isEqualTo("약관상 환불 불가 기간");
        assertThat(request.getProcessedBy()).isEqualTo(ADMIN);
    }

    @Test
    void 반려_사유는_비울_수_없다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, null);

        assertThatThrownBy(() -> request.reject(ADMIN, " ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request.reject(ADMIN, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 이미_닫힌_요청은_다시_닫지_않는다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, null);
        request.complete(ADMIN, NOW);

        assertThatThrownBy(() -> request.reject(ADMIN, "사유", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.complete(ADMIN, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 처리자와_시각이_없으면_닫지_않는다() {
        RefundRequest request = RefundRequest.requestAll(OWNER, 12000L, null);

        assertThatThrownBy(() -> request.complete(null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request.complete(ADMIN, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
