package com.launchcatch.adserving.engine;

/*
 * 캠페인 하나의 그날 예산 진행과 유효 노출 수다.
 * 진행률이 1.2 를 넘거나 유효 노출 수가 노출 상한(그날 예약액 / 단가 x 1.05)에 닿으면 그날 더 내보내지 않는다(FR-096).
 */
public record CampaignPacing(double progressRate, long reservedAmount, long unitPrice, long validImpressions) {

    static final double MAX_PROGRESS_RATE = 1.2;

    // 상한을 실수로 나누지 않고 양변에 단가와 100 을 곱해 정수로 비교한다.
    public boolean blocked() {
        return progressRate > MAX_PROGRESS_RATE || validImpressions * unitPrice * 100 >= reservedAmount * 105;
    }
}
