package com.launchcatch.adserving.engine.score;

import com.launchcatch.adserving.engine.entity.DiscountType;
import java.util.Map;

// 점수를 매길 때 후보 묶음 전체에서 정해지는 이번 요청의 탐색 반경과 할인 종류별 최댓값이다
public record ScoringPool(int radiusM, Map<DiscountType, Integer> maxDiscount) {
}
