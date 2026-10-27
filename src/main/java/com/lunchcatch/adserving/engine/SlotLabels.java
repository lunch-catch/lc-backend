package com.lunchcatch.adserving.engine;

/*
 * 지금 엔진이 붙이는 슬롯 이름표다. 저장과 분석은 이 두 값을 알지 않고 받은 이름표를 그대로 남기고 센다(#978).
 * 피드 구성을 바꾸면 새 이름표를 여기 더하면 된다. 마이그레이션과 분석 코드는 바꾸지 않는다.
 * 이름표는 대문자, 숫자, 밑줄만 쓴다. 두 로그의 CHECK 가 막고, serve 기록이 콜론으로 값을 잇기 때문이다.
 */
public final class SlotLabels {

    // 예산 소진 진행률 순으로 고르는 1, 4, 7번 자리다(FR-093)
    public static final String ALLOCATION = "ALLOCATION";

    // 관련성 점수 순으로 고르는 나머지 자리다(FR-094)
    public static final String RELEVANCE = "RELEVANCE";

    private SlotLabels() {
    }
}
