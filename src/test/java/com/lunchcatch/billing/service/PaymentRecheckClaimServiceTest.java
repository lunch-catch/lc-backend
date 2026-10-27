package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.config.PaymentRecheckProperties;
import com.lunchcatch.billing.entity.PaymentReconciliationOutbox;
import com.lunchcatch.billing.repository.PaymentReconciliationOutboxRepository;
import com.lunchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecheckClaimServiceTest {

    // 2026-10-11 10:00 KST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T01:00:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 11, 10, 0);

    @Mock PaymentReconciliationOutboxRepository outboxRepository;

    private PaymentRecheckClaimService service() {
        return new PaymentRecheckClaimService(outboxRepository, new PaymentRecheckProperties(5,
                Duration.ofMinutes(1), List.of(Duration.ofMinutes(2)), 0.1, 20, Duration.ofMinutes(5)), CLOCK);
    }

    @Test
    void 시각이_된_행을_선점해_다음_시각을_선점_만료로_미루고_결제_ID를_돌려준다() {
        PaymentReconciliationOutbox first = PaymentReconciliationOutbox.pending(11L, 5, NOW.minusMinutes(3));
        PaymentReconciliationOutbox second = PaymentReconciliationOutbox.pending(12L, 5, NOW.minusMinutes(1));
        when(outboxRepository.findDueForClaim(NOW, 20)).thenReturn(List.of(first, second));

        List<Long> claimed = service().claimDue();

        assertThat(claimed).containsExactly(11L, 12L);
        assertThat(first.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(second.getNextRetryAt()).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    void 대상이_없으면_빈_목록이다() {
        when(outboxRepository.findDueForClaim(NOW, 20)).thenReturn(List.of());

        assertThat(service().claimDue()).isEmpty();
    }
}
