package com.launchcatch.analytics.batch;

import java.time.LocalDate;
import java.util.List;

/*
 * 하루 원장 합계를 정산이 내준 저장 부호 그대로 담는다.
 *
 * reserve, deduct, refund, negativeAdjust 는 0 이하이고 charge, release, positiveAdjust 는 0 이상이다.
 * correctionDates 는 그날 기록된 정정이 가리키는 원래 영업일이다.
 */
public record SettlementMetrics(
        long charge,
        long reserve,
        long deduct,
        long release,
        long refund,
        long positiveAdjust,
        long negativeAdjust,
        long unspentBalance,
        List<LocalDate> correctionDates
) {
}
