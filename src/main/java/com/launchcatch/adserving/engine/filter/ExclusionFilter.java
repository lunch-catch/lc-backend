package com.launchcatch.adserving.engine.filter;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;

// 그날 찜하거나 패스한 캠페인은 다시 내보내지 않는다
public final class ExclusionFilter implements CandidateFilter {

    @Override
    public boolean accepts(FeedCandidate candidate, FeedContext context) {
        return !context.excludedCampaignIds().contains(candidate.campaignId());
    }
}
