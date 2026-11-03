package com.lunchcatch.billing.job;

import com.lunchcatch.billing.service.RechargeReservationRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 승인이 남긴 재예약 대기 행을 주기마다 처리한다(I-03).
 *
 * 배치 서버가 두 대여도 batch_execution_log 로 점유하지 않는다. 결제 재조회 워커처럼 대상 행을
 * SKIP LOCKED 로 선점하므로 두 서버가 같은 행을 동시에 처리하지 않는다.
 *
 * @EnableScheduling 은 SchedulingConfig 에만 있고 lunchcatch.scheduler.enabled=true 일 때만 뜬다.
 * 그 값은 application-batch.yml 에만 있어 API 서버에서는 이 메서드가 돌지 않는다.
 *
 * 앞 실행이 끝난 뒤 주기를 센다(fixedDelay). 재예약이 느려도 실행이 겹쳐 쌓이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RechargeReservationWorker {

    private final RechargeReservationRetryService retryService;

    @Scheduled(fixedDelayString = "${billing.recharge-reservation.interval}")
    public void reserveCharged() {
        int handled = retryService.reserveDue();
        if (handled > 0) {
            log.info("event=BILLING_RECHARGE_RESERVE_RUN handledCount={}", handled);
        }
    }
}
