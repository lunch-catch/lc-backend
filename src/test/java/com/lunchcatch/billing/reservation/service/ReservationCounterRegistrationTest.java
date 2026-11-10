package com.lunchcatch.billing.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

// 기동 때 결과 라벨마다 카운터가 0 으로 있어야 경보가 첫 사건의 증가를 센다(#1027)
class ReservationCounterRegistrationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void 재예약_결과마다_카운터를_0으로_만든다() {
        new RechargeReservationRetryService(null, null, null, null, registry, null).registerCounters();

        for (String value : List.of("done", "failed", "expired", "error")) {
            assertThat(registry.get(RechargeReservationRetryService.RESERVE_METRIC).tag("result", value)
                    .counter().count()).as(value).isZero();
        }
    }
}
