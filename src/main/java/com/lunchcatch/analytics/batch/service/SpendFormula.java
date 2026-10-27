package com.lunchcatch.analytics.batch.service;

/*
 * 소진액 계산을 한 곳에 둔다. 매출(A-07)과 캠페인 리포트가 같은 식을 써서 두 화면의 숫자가 갈리지 않는다.
 *
 * 소진액 = DEDUCT 합 - 양수 ADJUST 합이다. 양수 ADJUST 는 모두 과오 차감 정정으로 본다. 정정이 그날
 * 차감보다 크면 음수일 수 있고 0 으로 자르지 않는다. 두 인자는 절댓값(0 이상)이다.
 */
public final class SpendFormula {

    private SpendFormula() {
    }

    public static long spentAmount(long deductAmount, long positiveAdjustAmount) {
        return deductAmount - positiveAdjustAmount;
    }
}
