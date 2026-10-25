package com.launchcatch.adserving.engine.score;

import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.adserving.engine.FeedContext;

// 최근 30일 찜 가운데 그 업종의 비율이고 찜이 없으면 0 이다
public final class CategorySignal implements RelevanceSignal {

    @Override
    public double weight() {
        return 0.35;
    }

    @Override
    public double score(Placed placed, FeedContext context, ScoringPool pool) {
        return context.categoryPreference().getOrDefault(placed.candidate().categoryCode(), 0.0);
    }
}
