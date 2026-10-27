package com.lunchcatch.adserving.engine.filter;

import com.lunchcatch.adserving.engine.FeedCandidate;
import com.lunchcatch.adserving.engine.FeedContext;

// 피드 필터에서 고른 업종만 내보내되 고른 업종이 없으면 전부 내보낸다(FR-067)
public final class CategoryFilter implements CandidateFilter {

    @Override
    public boolean accepts(FeedCandidate candidate, FeedContext context) {
        return context.categories().isEmpty() || context.categories().contains(candidate.categoryCode());
    }
}
