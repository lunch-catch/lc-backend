package com.lunchcatch.billing.job;

import static org.mockito.Mockito.verify;

import com.lunchcatch.billing.service.DailyReservationService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 해제 완료 표시는 예약 서비스가 날짜마다 남긴다. 이 단계는 예약만 부른다
@ExtendWith(MockitoExtension.class)
class DailyReservationJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Mock DailyReservationService reservation;

    @Test
    void 그날_예약을_부른다() {
        new DailyReservationJob(reservation).run(DAY);

        verify(reservation).reserve(DAY);
    }
}
