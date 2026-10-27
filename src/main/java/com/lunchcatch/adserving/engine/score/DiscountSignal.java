package com.lunchcatch.adserving.engine.score;

import com.lunchcatch.adserving.engine.FeedAssembler.Placed;
import com.lunchcatch.adserving.engine.FeedCandidate;
import com.lunchcatch.adserving.engine.FeedContext;
import com.lunchcatch.adserving.engine.entity.DiscountType;
import java.util.Map;

// 메뉴 가격이 없어 PERCENT 와 AMOUNT 를 직접 비교할 수 없으므로 같은 종류의 최댓값으로 나눈다
public final class DiscountSignal implements RelevanceSignal {

    @Override
    public double weight() {
        return 0.20;
    }

    @Override
    public double score(Placed placed, FeedContext context, ScoringPool pool) {
        return of(placed.candidate(), pool.maxDiscount());
    }

    // 할인순 표시 정렬도 같은 값을 쓴다
    public static double of(FeedCandidate candidate, Map<DiscountType, Integer> maxDiscount) {
        return (double) candidate.discountValue() / maxDiscount.get(candidate.discountType());
    }
}
