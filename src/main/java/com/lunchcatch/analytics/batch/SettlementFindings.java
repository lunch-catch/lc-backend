package com.lunchcatch.analytics.batch;

/*
 * 정산 정합성 검사에서 모으기가 만든 결과다. 위반 수는 캠페인 수이고 expectedSpent 는 원장 합에서 다시
 * 계산한 플랫폼 소진액이다. 공개 때 방금 쓴 행과 맞대는 값이다.
 */
public record SettlementFindings(
        long billedAmountViolations,
        long overDeductViolations,
        long releaseViolations,
        long expectedSpent
) {
}
