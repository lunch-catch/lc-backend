package com.lunchcatch.billing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.reservation.config.RechargeReservationRetryProperties;
import com.lunchcatch.billing.reservation.entity.RechargeReservationOutbox;
import com.lunchcatch.billing.reservation.entity.RechargeReservationStatus;
import com.lunchcatch.billing.reservation.repository.RechargeReservationOutboxRepository;
import com.lunchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RechargeReservationOutboxServiceTest {

    // 2026-10-11 10:00 KST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 0);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);
    private static final Long OUTBOX_ID = 5L;

    @Mock RechargeReservationOutboxRepository outboxRepository;

    private RechargeReservationOutboxService service() {
        return new RechargeReservationOutboxService(outboxRepository, new RechargeReservationRetryProperties(
                List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5)), 20,
                Duration.ofMinutes(5), Duration.ofSeconds(50)), CLOCK);
    }

    private RechargeReservationOutbox stored() {
        RechargeReservationOutbox outbox = RechargeReservationOutbox.pending(101L, DAY, NOW.minusMinutes(1));
        when(outboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.of(outbox));
        return outbox;
    }

    @Test
    void 시각이_된_행을_선점해_다음_시각을_선점_만료로_미루고_그_행을_돌려준다() {
        RechargeReservationOutbox first = RechargeReservationOutbox.pending(11L, DAY, NOW.minusMinutes(3));
        RechargeReservationOutbox second = RechargeReservationOutbox.pending(12L, DAY, NOW.minusMinutes(1));
        when(outboxRepository.findDueForClaim(NOW, 20)).thenReturn(List.of(first, second));

        List<RechargeReservationOutbox> claimed = service().claimDue();

        assertThat(claimed).containsExactly(first, second);
        assertThat(first.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(second.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void 대상이_없으면_빈_목록이다() {
        when(outboxRepository.findDueForClaim(NOW, 20)).thenReturn(List.of());

        assertThat(service().claimDue()).isEmpty();
    }

    @Test
    void 재예약을_마친_행을_DONE으로_닫는다() {
        RechargeReservationOutbox outbox = stored();

        service().complete(OUTBOX_ID);

        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.DONE);
    }

    @Test
    void 영업일이_지난_행을_EXPIRED로_닫는다() {
        RechargeReservationOutbox outbox = stored();

        service().expire(OUTBOX_ID);

        assertThat(outbox.getStatus()).isEqualTo(RechargeReservationStatus.EXPIRED);
    }

    @Test
    void 첫_실패는_첫_간격_뒤로_미룬다() {
        RechargeReservationOutbox outbox = stored();

        service().recordFailure(OUTBOX_ID, "오류");

        assertThat(outbox.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(outbox.getLastError()).isEqualTo("오류");
    }

    @Test
    void 실패가_쌓이면_그_행의_실패_횟수만큼_간격을_늘린다() {
        RechargeReservationOutbox outbox = stored();
        outbox.recordFailure("오류", NOW);
        outbox.recordFailure("오류", NOW);

        service().recordFailure(OUTBOX_ID, "오류");

        assertThat(outbox.getAttemptCount()).isEqualTo(3);
        assertThat(outbox.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void 행이_없으면_결과를_적지_않고_예외를_던진다() {
        when(outboxRepository.findById(OUTBOX_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(OUTBOX_ID)).isInstanceOf(IllegalStateException.class);
    }
}
