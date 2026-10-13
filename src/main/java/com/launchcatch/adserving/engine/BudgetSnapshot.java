package com.launchcatch.adserving.engine;

import com.launchcatch.billing.contract.BudgetProgress;
import java.util.Map;

/*
 * 피드 한 번을 배정하는 동안 쓰는 캠페인별 예산 상태다.
 * progress 는 정산이 준 그날 진행률이고 validImpressions 는 광고 서빙이 노출 로그로 센 그날 유효 노출 수다.
 * known 이 false 면 예산 조건 없이 배정한다. 정산의 조회 구현이 아직 없는 동안(#193)만 그렇다.
 */
public record BudgetSnapshot(boolean known, Map<Long, BudgetProgress> progress, Map<Long, Long> validImpressions) {

    public static final BudgetSnapshot UNKNOWN = new BudgetSnapshot(false, Map.of(), Map.of());

    // 예산 소진 진행률이 이 값을 넘으면 두 슬롯 모두에서 뺀다(99행)
    static final double MAX_PROGRESS_RATE = 1.2;

    /*
     * 노출 상한은 그날 예약액 / 그날 단가 x 1.05 다(99행).
     * 정수로 계산하려고 백분율로 둔다
     */
    private static final long CAP_PERCENT = 105;

    public BudgetSnapshot {
        progress = Map.copyOf(progress);
        validImpressions = Map.copyOf(validImpressions);
    }

    public static BudgetSnapshot of(Map<Long, BudgetProgress> progress, Map<Long, Long> validImpressions) {
        return new BudgetSnapshot(true, progress, validImpressions);
    }

    /*
     * 예산 때문에 이 캠페인을 빼야 하는지 본다.
     * 그날 계획이 없는 캠페인은 정산이 결과에서 빼므로 진행률이 없으면 후보가 아니다.
     */
    boolean excludes(long campaignId) {
        if (!known) {
            return false;
        }
        BudgetProgress budget = progress.get(campaignId);
        if (budget == null || budget.progressRate() > MAX_PROGRESS_RATE) {
            return true;
        }
        long cap = budget.reservedAmount() * CAP_PERCENT / (budget.unitPrice() * 100);
        return validImpressions.getOrDefault(campaignId, 0L) >= cap;
    }

    /*
     * 배분 슬롯의 정렬 키다.
     * 예산을 모르면 모두 같은 값이라 다음 키가 순서를 정한다
     */
    double progressRate(long campaignId) {
        BudgetProgress budget = progress.get(campaignId);
        return budget == null ? 0 : budget.progressRate();
    }
}
