package com.lunchcatch.billing.job;

import com.lunchcatch.billing.service.DailyReservationService;
import com.lunchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 예약 단계다(V52 102행 (5)). 순서 값은 102행 단계 번호 × 10 이다.
 *
 * 예약은 미해제 날짜와 전날을 먼저 RELEASE 한다. 서빙이 하루 종일이라 이 해제가 전날의 정식 해제다.
 * 해제 완료 표시(BILLING_DAILY_RELEASE)는 날짜마다 그 해제가 끝나는 즉시 예약 서비스가 남긴다. 분석 01:00 집계가 이 행으로
 * 원천이 확정됐는지 보므로, 오늘 예약이 실패해도 해제가 끝난 날짜의 표시는 늦어지지 않는다.
 */
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
