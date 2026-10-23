package com.launchcatch.billing.job;

import com.launchcatch.billing.service.PaymentRecheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 확인 중(UNKNOWN)인 결제를 주기마다 다시 조회한다.
 *
 * 배치 서버가 두 대여도 batch_execution_log 로 점유하지 않는다. 그 표는 영업일 하루에 한 번 도는
 * 작업을 위한 것이고, 이 작업은 1분 주기라 맞지 않는다. 대신 대상 행을 SKIP LOCKED 로 선점하므로
 * 두 서버가 같은 결제를 동시에 조회하지 않는다.
 *
 * @EnableScheduling 은 SchedulingConfig 에만 있고 launchcatch.scheduler.enabled=true 일 때만 뜬다.
 * 그 값은 application-batch.yml 에만 있어 API 서버에서는 이 메서드가 돌지 않는다.
 *
 * 앞 실행이 끝난 뒤 주기를 센다(fixedDelay). 결제대행사가 느려도 실행이 겹쳐 쌓이지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecheckWorker {

    private final PaymentRecheckService recheckService;

    @Scheduled(fixedDelayString = "${billing.payment.recheck.interval}")
    public void recheckUnknownPayments() {
        int handled = recheckService.recheckDue();
        if (handled > 0) {
            log.info("event=BILLING_PAYMENT_RECHECK_RUN handledCount={}", handled);
        }
    }
}
