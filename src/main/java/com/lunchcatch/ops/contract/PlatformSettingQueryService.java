package com.lunchcatch.ops.contract;

/** 플랫폼 설정값을 공개한다. */
public interface PlatformSettingQueryService {

    /**
     * 노출 1건당 단가(원)를 돌려준다. IMPRESSION_UNIT_PRICE 키가 없거나 정수로 파싱되지
     * 않으면 예외를 던진다. 기본값으로 떨어지지 않는다(#198). 조용히 0이나 10으로 진행하면
     * 모든 캠페인이 예산 부족(NO_POINTS)으로 판정될 수 있다.
     */
    int getImpressionUnitPrice();

    /**
     * 캠페인이 설정할 수 있는 최소 하루 예산(포인트)을 돌려준다. MIN_DAILY_BUDGET 키가
     * 없거나 정수로 파싱되지 않으면 예외를 던진다. 기본값으로 떨어지지 않는다
     * (getImpressionUnitPrice와 같은 이유, #198).
     */
    long getMinDailyBudget();
}
