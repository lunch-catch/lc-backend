package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 아웃박스 행의 시작 상태와 닫기를 본다.
 * PENDING 인데 다음 재조회 시각이 없으면 DB CHECK 가 저장을 거부하고, 그 순간은 결제를 UNKNOWN 으로 저장하려는 때다.
 */
class PaymentReconciliationOutboxTest {

    private static final Long PAYMENT_ID = 101L;
    private static final LocalDateTime NEXT = LocalDateTime.of(2026, 10, 11, 10, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 5);

    @Test
    @DisplayName("재조회 대기 행은 PENDING, 시도 0회로 시작하고 다음 재조회 시각을 갖는다")
    void 대기_행_생성() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NEXT);

        assertThat(outbox.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getAttemptCount()).isZero();
        assertThat(outbox.getMaxRetry()).isEqualTo(10);
        assertThat(outbox.getNextRetryAt()).isEqualTo(NEXT);
        assertThat(outbox.getLastCheckedAt()).isNull();
        assertThat(outbox.getLastError()).isNull();
    }

    @Test
    @DisplayName("결제 ID, 다음 재조회 시각이 없거나 상한이 음수면 만들 수 없다")
    void 필수값_검증() {
        assertThatThrownBy(() -> PaymentReconciliationOutbox.pending(null, 10, NEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReconciliationOutbox.pending(PAYMENT_ID, -1, NEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("결과가 확정되면 RESOLVED 로 닫고 확인 시각을 남긴다")
    void 닫기() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NEXT);

        outbox.resolve(NOW);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.RESOLVED);
        assertThat(outbox.getLastCheckedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("이미 닫힌 행을 다시 닫아도 처음 확인 시각을 유지한다")
    void 중복_닫기() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NEXT);
        outbox.resolve(NOW);

        outbox.resolve(NOW.plusMinutes(3));

        assertThat(outbox.getLastCheckedAt()).isEqualTo(NOW);
    }

    // 상한을 넘겨 굳은 건을 관리자 재조회가 확정하는 경로다
    @Test
    @DisplayName("재조회 상한을 넘긴 행도 닫을 수 있다")
    void 영구_실패_닫기() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NEXT);
        ReflectionTestUtils.setField(outbox, "status", OutboxStatus.FAILED_PERMANENT);

        outbox.resolve(NOW);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.RESOLVED);
    }

    @Test
    @DisplayName("확인 시각 없이는 닫을 수 없다")
    void 확인_시각_필수() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 10, NEXT);

        assertThatThrownBy(() -> outbox.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("선점하면 다음 재조회 시각이 선점 만료 시각으로 밀리고 PENDING 은 유지된다")
    void 선점() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);

        outbox.claim(NOW.plusMinutes(5));

        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("선점 만료 시각이 없거나 PENDING 이 아닌 행은 선점할 수 없다")
    void 선점_검증() {
        PaymentReconciliationOutbox pending = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);
        PaymentReconciliationOutbox resolved = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);
        resolved.resolve(NOW);

        assertThatThrownBy(() -> pending.claim(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolved.claim(NOW.plusMinutes(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("확정하지 못하면 시도 횟수가 늘고 확인 시각, 사유, 다음 재조회 시각이 기록된다")
    void 미확정_기록() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);

        boolean exhausted = outbox.recordMiss(NOW, "PG timeout", NOW.plusMinutes(2));

        assertThat(exhausted).isFalse();
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getLastCheckedAt()).isEqualTo(NOW);
        assertThat(outbox.getLastError()).isEqualTo("PG timeout");
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(2));
    }

    @Test
    @DisplayName("시도 횟수가 상한에 닿으면 FAILED_PERMANENT 가 되고 다음 재조회 시각이 비워진다")
    void 상한_도달() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 1, NEXT);

        boolean exhausted = outbox.recordMiss(NOW, "PG timeout", NOW.plusMinutes(2));

        assertThat(exhausted).isTrue();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED_PERMANENT);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("사유는 500자까지만 남긴다")
    void 사유_절단() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);

        outbox.recordMiss(NOW, "x".repeat(600), NOW.plusMinutes(2));

        assertThat(outbox.getLastError()).hasSize(500);
    }

    @Test
    @DisplayName("확인 시각이 없거나 PENDING 이 아니거나 상한 전인데 다음 시각이 없으면 기록할 수 없다")
    void 미확정_기록_검증() {
        PaymentReconciliationOutbox pending = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);
        PaymentReconciliationOutbox permanent = PaymentReconciliationOutbox.pending(PAYMENT_ID, 0, NEXT);
        permanent.recordMiss(NOW, "e", null);

        assertThatThrownBy(() -> pending.recordMiss(null, "e", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.recordMiss(NOW, "e", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> permanent.recordMiss(NOW, "e", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("관리자 재조회가 확정하지 못하면 시도 횟수와 상태는 두고 확인 시각과 사유만 남긴다")
    void 확인_시각만_기록() {
        PaymentReconciliationOutbox outbox = PaymentReconciliationOutbox.pending(PAYMENT_ID, 0, NEXT);
        outbox.recordMiss(NOW, "first", null);

        outbox.touch(NOW.plusHours(1), "again");

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED_PERMANENT);
        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getLastCheckedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(outbox.getLastError()).isEqualTo("again");
    }

    @Test
    @DisplayName("이미 닫힌 행은 확인 시각을 갱신하지 않고 확인 시각 없이는 기록할 수 없다")
    void 확인_시각_기록_검증() {
        PaymentReconciliationOutbox resolved = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);
        resolved.resolve(NOW);
        PaymentReconciliationOutbox pending = PaymentReconciliationOutbox.pending(PAYMENT_ID, 5, NEXT);

        resolved.touch(NOW.plusHours(1), "ignored");

        assertThat(resolved.getLastCheckedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> pending.touch(null, "e")).isInstanceOf(IllegalArgumentException.class);
    }
}
