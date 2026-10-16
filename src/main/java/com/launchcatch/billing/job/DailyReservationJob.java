package com.launchcatch.billing.job;

import com.launchcatch.billing.service.DailyReservationService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 00:00 묶음의 예약 단계다(V52 102행 (5)). 순서 값은 102행 단계 번호 × 10 이다
@Component
@Order(50)
@RequiredArgsConstructor
public class DailyReservationJob implements DailyJob {

    static final String JOB_NAME = "BILLING_RESERVE";

    private final DailyReservationService reservation;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        reservation.reserve(businessDate);
    }
}
