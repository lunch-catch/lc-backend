package com.launchcatch.adserving.engine.filter;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;

// 같은 캠페인은 한 사용자에게 하루 2회, 한 세션 1회까지 내보낸다(비즈니스 규칙)
public final class FrequencyFilter implements CandidateFilter {

    static final int DAILY_SERVE_LIMIT = 2;

    @Override
    public boolean accepts(FeedCandidate candidate, FeedContext context) {
        return context.dailyServedCounts().getOrDefault(candidate.campaignId(), 0) < DAILY_SERVE_LIMIT
                && !context.sessionServedCampaignIds().contains(candidate.campaignId());
    }
}
