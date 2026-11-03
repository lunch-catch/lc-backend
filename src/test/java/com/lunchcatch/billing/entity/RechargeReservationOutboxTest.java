package com.lunchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RechargeReservationOutboxTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 0);

    private static RechargeReservationOutbox pending() {
        return RechargeReservationOutbox.pending(101L, DAY, NOW);
    }

    @Test
    void 만들면_재예약을_기다리고_시도_횟수는_0이다() {
        RechargeReservationOutbox outbox = pending();

        assertThat(outbox.getPaymentId()).isEqualTo(101L);
        assertThat(outbox.getBusinessDate()).isEqualTo(DAY);
        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.PENDING);
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW);
        assertThat(outbox.getAttemptCount()).isZero();
    }

    @Test
    void 결제_영업일_시각이_없으면_만들지_않는다() {
        assertThatThrownBy(() -> RechargeReservationOutbox.pending(null, DAY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RechargeReservationOutbox.pending(101L, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RechargeReservationOutbox.pending(101L, DAY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 선점하면_다음_시각을_선점_만료로_미룬다() {
        RechargeReservationOutbox outbox = pending();

        outbox.claim(NOW.plusMinutes(5));

        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.PENDING);
    }

    @Test
    void 닫힌_행은_선점하지_않는다() {
        RechargeReservationOutbox outbox = pending();
        outbox.complete();

        assertThatThrownBy(() -> outbox.claim(NOW.plusMinutes(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 재예약을_마치면_DONE으로_닫고_다음_시각을_비운다() {
        RechargeReservationOutbox outbox = pending();

        outbox.complete();

        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.DONE);
        assertThat(outbox.getNextRetryAt()).isNull();
    }

    @Test
    void 영업일이_지나면_EXPIRED로_닫고_다음_시각을_비운다() {
        RechargeReservationOutbox outbox = pending();

        outbox.expire();

        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.EXPIRED);
        assertThat(outbox.getNextRetryAt()).isNull();
    }

    // 선점이 만료돼 두 서버가 같은 행을 처리했을 때 뒤에 온 쪽이 앞의 결과를 바꾸지 않는다
    @Test
    void 이미_닫힌_행은_다시_닫아도_그대로다() {
        RechargeReservationOutbox done = pending();
        done.complete();
        RechargeReservationOutbox expired = pending();
        expired.expire();

        done.expire();
        expired.complete();

        assertThat(done.getStatus()).isEqualTo(RechargeReservationStatus.DONE);
        assertThat(expired.getStatus()).isEqualTo(RechargeReservationStatus.EXPIRED);
    }

    @Test
    void 실패하면_시도_횟수를_늘리고_사유와_다음_시각을_남긴다() {
        RechargeReservationOutbox outbox = pending();

        outbox.recordFailure("CannotAcquireLockException: 잠금 대기 시간 초과", NOW.plusMinutes(1));
        outbox.recordFailure("CannotAcquireLockException: 잠금 대기 시간 초과", NOW.plusMinutes(3));

        assertThat(outbox.getAttemptCount()).isEqualTo(2);
        assertThat(outbox.getLastError()).isEqualTo("CannotAcquireLockException: 잠금 대기 시간 초과");
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(3));
        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.PENDING);
    }

    @Test
    void 실패_사유는_500자로_자른다() {
        RechargeReservationOutbox outbox = pending();

        outbox.recordFailure("x".repeat(600), NOW.plusMinutes(1));

        assertThat(outbox.getLastError()).hasSize(500);
    }

    @Test
    void 닫힌_행에는_실패를_적지_않는다() {
        RechargeReservationOutbox outbox = pending();
        outbox.complete();

        assertThatThrownBy(() -> outbox.recordFailure("오류", NOW.plusMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패를_적을_때_다음_시각이_없으면_거절한다() {
        RechargeReservationOutbox outbox = pending();

        assertThatThrownBy(() -> outbox.recordFailure("오류", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
