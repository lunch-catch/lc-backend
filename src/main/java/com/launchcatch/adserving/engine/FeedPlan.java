package com.launchcatch.adserving.engine;

import java.util.List;

/*
 * 배정 결과다.
 * radiusM 은 후보를 찾는 데 실제로 쓴 탐색 반경이다
 */
public record FeedPlan(int radiusM, List<PlannedCard> cards) {

    public FeedPlan {
        cards = List.copyOf(cards);
    }
}
