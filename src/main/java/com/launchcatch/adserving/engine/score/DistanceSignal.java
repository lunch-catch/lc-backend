package com.launchcatch.adserving.engine.score;

import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.adserving.engine.FeedContext;

// 가까울수록 높은 값인 1 - 거리 / 이번 요청의 탐색 반경이다
public final class DistanceSignal implements RelevanceSignal {

    @Override
    public double weight() {
        return 0.45;
    }

    @Override
    public double score(Placed placed, FeedContext context, ScoringPool pool) {
        return 1 - placed.exactDistanceM() / pool.radiusM();
    }
}
