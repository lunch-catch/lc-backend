package com.launchcatch.billing.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.launchcatch.billing.dto.PointCharged;
import com.launchcatch.billing.service.RechargeReservationService;
import org.junit.jupiter.api.Test;

class PointChargedListenerTest {

    private static final PointCharged CHARGED = new PointCharged(7L, 42L, 10000L);

    @Test
    void 받은_충전_통보를_재예약_서비스에_그대로_넘긴다() {
        RechargeReservationService service = mock(RechargeReservationService.class);

        new PointChargedListener(service).on(CHARGED);

        verify(service).reserve(CHARGED);
    }

    // 충전은 이미 커밋돼 있다. 올리면 승인 확정 API가 성공한 충전에 실패를 돌려준다
    @Test
    void 재예약이_실패해도_예외를_올리지_않는다() {
        RechargeReservationService service = mock(RechargeReservationService.class);
        doThrow(new IllegalStateException("재예약 실패")).when(service).reserve(CHARGED);

        assertThatCode(() -> new PointChargedListener(service).on(CHARGED)).doesNotThrowAnyException();
    }
}
