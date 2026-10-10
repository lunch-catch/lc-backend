package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 상태와 컬럼의 짝을 지킨다.
 *
 * payment 의 CHECK 다섯 개가 상태마다 어느 컬럼이 있어야 하는지를 DB 에서 강제한다. 그 짝이
 * 어긋난 전이를 코드가 먼저 막는지 본다. DB 까지 가서 거부되면 그 순간은 결제 결과를
 * 기록하려는 때이고, 그러면 결과 자체가 사라진다.
 */
class PaymentTest {

    private static final Long OWNER_ID = 7L;
    private static final String ORDER_ID = "lc-20261009-0001";
    private static final String PG_TID = "tviva20261009000001";
    private static final long AMOUNT = 10000L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 15, 0);

    private Payment requested() {
        return Payment.order(OWNER_ID, ORDER_ID, AMOUNT);
    }

    private Payment withPgTid() {
        Payment payment = requested();
        payment.assignPgTid(PG_TID);
        return payment;
    }

    @Test
    @DisplayName("주문을 만들면 승인을 기다리는 상태로 시작한다")
    void 주문_생성() {
        Payment payment = requested();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(payment.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(payment.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(payment.getAmount()).isEqualTo(AMOUNT);
        assertThat(payment.getPgTid()).isNull();
        assertThat(payment.getApprovedAt()).isNull();
        assertThat(payment.getFailureReason()).isNull();
        assertThat(payment.getCanceledAt()).isNull();
    }

    @Test
    @DisplayName("금액이 0 이하인 주문은 만들 수 없다")
    void 금액_검증() {
        assertThatThrownBy(() -> Payment.order(OWNER_ID, ORDER_ID, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("주문번호가 비거나 64자를 넘으면 만들 수 없다")
    void 주문번호_검증() {
        assertThatThrownBy(() -> Payment.order(OWNER_ID, " ", AMOUNT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payment.order(OWNER_ID, "a".repeat(65), AMOUNT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("점주 ID가 없으면 만들 수 없다")
    void 점주_검증() {
        assertThatThrownBy(() -> Payment.order(null, ORDER_ID, AMOUNT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결제 식별자는 승인을 기다리는 주문에만 적는다")
    void 결제식별자_적기() {
        Payment payment = withPgTid();

        assertThat(payment.getPgTid()).isEqualTo(PG_TID);
    }

    /*
     * 같은 주문으로 승인을 다시 시도하는 경로가 있다. 요청이 결제대행사에 가지 않은 것이
     * 확실한 경우가 그렇다. 그때 같은 값을 다시 적는 것이 막히면 재시도 자체가 막힌다.
     */
    @Test
    @DisplayName("같은 결제 식별자를 다시 적는 것은 허용한다")
    void 결제식별자_재적용() {
        Payment payment = withPgTid();

        payment.assignPgTid(PG_TID);

        assertThat(payment.getPgTid()).isEqualTo(PG_TID);
    }

    @Test
    @DisplayName("확정된 결제에는 다른 결제 식별자를 적을 수 없다")
    void 결제식별자_확정후_거부() {
        Payment payment = withPgTid();
        payment.approve(NOW);

        assertThatThrownBy(() -> payment.assignPgTid("other-pg-tid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("결제 식별자가 없으면 승인으로 확정할 수 없다")
    void 승인은_결제식별자를_요구한다() {
        Payment payment = requested();

        assertThatThrownBy(() -> payment.approve(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("승인으로 확정하면 승인 시각이 남는다")
    void 승인() {
        Payment payment = withPgTid();

        payment.approve(NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAt()).isEqualTo(NOW);
    }

    /*
     * 승인 확정은 승인 확정 API 와 웹훅, 재조회 워커, 관리자 재조회가 함께 쓴다.
     * 네 경로가 같은 결제를 확정하려 할 수 있어 두 번 불려도 결과가 같아야 한다.
     */
    @Test
    @DisplayName("승인을 두 번 확정해도 처음 승인 시각이 남는다")
    void 승인_멱등() {
        Payment payment = withPgTid();
        payment.approve(NOW);

        payment.approve(NOW.plusHours(1));

        assertThat(payment.getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("실패로 확정하면 사유가 남는다")
    void 실패() {
        Payment payment = withPgTid();

        payment.fail("카드사 거절");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("카드사 거절");
        assertThat(payment.getApprovedAt()).isNull();
    }

    /*
     * 사유 컬럼은 255자다.
     * 넘치면 저장이 거부되어 실패 기록 자체가 사라진다.
     */
    @Test
    @DisplayName("실패 사유가 255자를 넘으면 잘라서 남긴다")
    void 실패사유_길이() {
        Payment payment = withPgTid();

        payment.fail("가".repeat(300));

        assertThat(payment.getFailureReason()).hasSize(255);
    }

    @Test
    @DisplayName("실패 사유가 없으면 실패로 확정할 수 없다")
    void 실패사유_필수() {
        Payment payment = withPgTid();

        assertThatThrownBy(() -> payment.fail(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과를 알 수 없는 결제는 뒤에 승인으로 확정할 수 있다")
    void 미확정_후_승인() {
        Payment payment = withPgTid();
        payment.markUnknown();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        payment.approve(NOW);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getApprovedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("취소된 주문은 승인으로 확정할 수 없다")
    void 취소_후_승인_거부() {
        Payment payment = withPgTid();
        payment.cancel(NOW, null);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCanceledAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> payment.approve(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("승인으로 확정한 결제는 실패로 바꿀 수 없다")
    void 승인_후_실패_거부() {
        Payment payment = withPgTid();
        payment.approve(NOW);

        assertThatThrownBy(() -> payment.fail("뒤늦은 실패"))
                .isInstanceOf(IllegalStateException.class);
    }
}
