package com.lunchcatch.billing.contract;

/*
 * 한 영업일의 플랫폼 전체 원장 합계다.
 *
 * unspentBalance 는 영업일 다음 날 00:00 이전에 실제로 기록된 CHARGE, REFUND, ADJUST, DEDUCT 의 누적 합이다.
 * 전 점주 잔액에 예약 중 포인트(예약액 - 소진 - 해제)를 더한 값과 같아서 해제가 실패해 예약이 남은 날도 쓰이지 않은 포인트를 센다.
 * ledgerSum 은 그 영업일의 기록 시각에 기록된 같은 네 유형의 합이라 전날 말 미소진 잔액에 더하면 unspentBalance 가 된다.
 * mismatchCount 는 영업일이 그날인 대사 불일치 건수이고 해결 여부를 가리지 않는다.
 */
public record DailyLedgerTotals(
        long unspentBalance,
        long ledgerSum,
        long mismatchCount
) {
}
