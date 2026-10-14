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
}
