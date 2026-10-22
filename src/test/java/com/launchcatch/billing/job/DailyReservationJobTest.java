package com.launchcatch.billing.job;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.launchcatch.billing.service.DailyReleaseMarker;
import com.launchcatch.billing.service.DailyReservationService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 분석 01:00 집계는 (BILLING_DAILY_RELEASE, 전날) 의 SUCCESS 로 전날 원천이 확정됐는지 본다
@ExtendWith(MockitoExtension.class)
class DailyReservationJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 19);

    @Mock DailyReservationService reservation;
    @Mock DailyReleaseMarker releaseMarker;

    private DailyReservationJob job;

    @BeforeEach
    void setUp() {
        job = new DailyReservationJob(reservation, releaseMarker);
    }

    @Test
    void 예약이_끝나면_전날_해제_표시를_남긴다() {
        job.run(DAY);

        InOrder order = inOrder(reservation, releaseMarker);
        order.verify(reservation).reserve(DAY);
        order.verify(releaseMarker).mark(PREV);
    }

    @Test
    void 예약이_실패하면_표시를_남기지_않는다() {
        doThrow(new IllegalStateException("예약 실패")).when(reservation).reserve(DAY);

        assertThatThrownBy(() -> job.run(DAY)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(releaseMarker);
    }
}
