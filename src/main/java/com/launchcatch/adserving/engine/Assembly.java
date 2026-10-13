package com.launchcatch.adserving.engine;

import java.util.List;

/*
 * 피드 한 번의 배정 결과다.
 * radiusM 은 후보를 찾는 데 실제로 쓴 탐색 반경이다.
 */
public record Assembly(int radiusM, List<AssignedCard> cards) {

    public Assembly {
        cards = List.copyOf(cards);
    }
}
