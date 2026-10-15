package com.launchcatch.billing.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.service.PaymentRecheckService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

@ExtendWith(MockitoExtension.class)
class PaymentRecheckWorkerTest {

    @Mock PaymentRecheckService recheckService;

    @Test
    void 실행하면_재조회_대상을_처리한다() {
        when(recheckService.recheckDue()).thenReturn(3);

        new PaymentRecheckWorker(recheckService).recheckUnknownPayments();

        verify(recheckService).recheckDue();
    }

    @Test
    void 대상이_없어도_오류_없이_끝난다() {
        when(recheckService.recheckDue()).thenReturn(0);

        new PaymentRecheckWorker(recheckService).recheckUnknownPayments();

        verify(recheckService).recheckDue();
    }

    @Test
    void 주기는_설정값에서_읽고_앞_실행이_끝난_뒤부터_센다() throws Exception {
        Method method = PaymentRecheckWorker.class.getMethod("recheckUnknownPayments");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${billing.payment.recheck.interval}");
    }
}
