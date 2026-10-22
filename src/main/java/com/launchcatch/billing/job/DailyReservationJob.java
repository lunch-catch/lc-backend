package com.launchcatch.billing.job;

import com.launchcatch.billing.service.DailyReleaseMarker;
import com.launchcatch.billing.service.DailyReservationService;
import com.launchcatch.ops.scheduler.DailyJob;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 00:00 묶음의 예약 단계다(V52 102행 (5)). 순서 값은 102행 단계 번호 × 10 이다.
 *
 * 예약은 전날 남은 예약을 먼저 RELEASE 한다. 서빙이 하루 종일이라 이 해제가 전날의 정식 해제다.
 * 예약까지 끝나면 (BILLING_DAILY_RELEASE, 전날) 을 SUCCESS 로 남긴다. 분석 01:00 집계가 이 행으로 전날 원천이 확정됐는지
 * 본다. 해제는 끝났지만 예약이 실패한 날은 표시가 늦어지고, 이 단계가 다시 돌아 성공할 때 남는다.
 * 전날보다 오래된 날짜의 해제를 이어 마친 경우의 표시는 예약이 그 날짜를 해제하는 즉시 남긴다.
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class DailyReservationJob implements DailyJob {

    static final String JOB_NAME = "BILLING_RESERVE";

    private final DailyReservationService reservation;
    private final DailyReleaseMarker releaseMarker;

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    @Override
    public void run(LocalDate businessDate) {
        reservation.reserve(businessDate);
        releaseMarker.mark(businessDate.minusDays(1));
    }
}
