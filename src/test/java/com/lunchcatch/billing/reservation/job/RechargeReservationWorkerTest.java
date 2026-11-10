package com.lunchcatch.billing.reservation.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.reservation.service.RechargeReservationRetryService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class RechargeReservationWorkerTest {

    @Mock RechargeReservationRetryService retryService;

    @Test
    void 실행하면_재예약_대상을_처리한다() {
        when(retryService.reserveDue()).thenReturn(3);

        new RechargeReservationWorker(retryService).reserveCharged();

        verify(retryService).reserveDue();
    }

    @Test
    void 대상이_없어도_오류_없이_끝난다() {
        when(retryService.reserveDue()).thenReturn(0);

        new RechargeReservationWorker(retryService).reserveCharged();

        verify(retryService).reserveDue();
    }

    @Test
    void 주기는_설정값에서_읽고_앞_실행이_끝난_뒤부터_센다() throws Exception {
        Method method = RechargeReservationWorker.class.getMethod("reserveCharged");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${billing.recharge-reservation.interval}");
    }
}
