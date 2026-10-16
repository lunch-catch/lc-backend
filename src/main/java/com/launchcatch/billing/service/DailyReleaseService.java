package com.launchcatch.billing.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 13:30 해제·대사의 진입점이다(구현 순서 11번). 당일 대조를 먼저 하고, 성공해야 해제한다.
 *
 * 대조가 실패하면 해제하지 않는다. 해제액이 그 대조가 읽은 원장 DEDUCT 합에 기대기 때문이다. 해제하지 못한
 * 몫은 다음 00:00 보정 RELEASE(DailyReservationService.release)가 정리한다.
 */
@Service
@RequiredArgsConstructor
public class DailyReleaseService {

    private final SpendReconciliationService reconciliation;
    private final DailyReservationService reservation;

    public void reconcileAndRelease(LocalDate businessDate, String jobName) {
        reconciliation.reconcileToday(businessDate, jobName);
        reservation.release(businessDate);
    }
}
