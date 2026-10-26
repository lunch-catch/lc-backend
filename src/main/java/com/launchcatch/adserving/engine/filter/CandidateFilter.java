package com.launchcatch.adserving.engine.filter;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;

/*
 * 후보 하나를 이 사용자에게 내보낼지 가르는 조건 하나다.
 * 조건을 더하려면 구현을 하나 더한다(#978).
 */
public interface CandidateFilter {

    boolean accepts(FeedCandidate candidate, FeedContext context);
}
