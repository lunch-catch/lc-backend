package com.lunchcatch.adserving.engine.score;

import com.lunchcatch.adserving.engine.FeedAssembler.Placed;
import com.lunchcatch.adserving.engine.FeedContext;

/*
 * 관련성 점수의 항목 하나다. 0~1 의 값에 가중치를 곱해 더한다.
 * 항목을 더하려면 구현을 하나 더한다(#978).
 */
public interface RelevanceSignal {

    double weight();

    double score(Placed placed, FeedContext context, ScoringPool pool);
}
