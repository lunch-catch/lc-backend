package com.launchcatch.adserving.engine.filter;

import com.launchcatch.adserving.engine.CampaignPacing;
import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;

/*
 * 진행률이 1.2 를 넘거나 노출 상한에 닿은 캠페인을 뺀다(FR-096).
 * 예산을 읽지 못했으면 예산 규칙을 건너뛴다(#307). 그날 예산 계획이 없는 캠페인은 진행 값이 없어 뺀다.
 */
public final class BudgetFilter implements CandidateFilter {

    @Override
    public boolean accepts(FeedCandidate candidate, FeedContext context) {
        if (!context.pacingAvailable()) {
            return true;
        }
        CampaignPacing pacing = context.pacing().get(candidate.campaignId());
        return pacing != null && !pacing.blocked();
    }
}
