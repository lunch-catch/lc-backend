package com.launchcatch.adserving.engine;

import com.launchcatch.billing.contract.BudgetProgress;

/*
 * 예산 때문에 후보를 두 슬롯 모두에서 빼는 규칙이다(99행).
 * 진행률이 1.2 를 넘거나, 그날 유효 노출 수가 노출 상한(그날 예약액 / 그날 단가 x 1.05)에 닿으면 뺀다.
 */
public final class BudgetRule {

    static final double MAX_PROGRESS_RATE = 1.2;

    /*
     * 1.05 를 정수 비율로 둔다.
     * double 로 곱하면 상한이 1 모자라게 내려앉는 값이 생긴다.
     */
    private static final long CAP_NUMERATOR = 105;
    private static final long CAP_DENOMINATOR = 100;

    private BudgetRule() {
    }

    public static boolean excludes(BudgetProgress progress, long validImpressions) {
        return progress.progressRate() > MAX_PROGRESS_RATE || validImpressions >= impressionCap(progress);
    }

    static long impressionCap(BudgetProgress progress) {
        return progress.reservedAmount() * CAP_NUMERATOR / (progress.unitPrice() * CAP_DENOMINATOR);
    }
}
