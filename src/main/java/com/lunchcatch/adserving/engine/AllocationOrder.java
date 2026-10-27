package com.lunchcatch.adserving.engine;

import com.lunchcatch.adserving.engine.FeedAssembler.Placed;
import java.util.Comparator;

/*
 * 배분 슬롯의 순서다. 예산 소진 진행률 오름차순, 같으면 먼저 만든 캠페인, 그다음 캠페인 ID 순이다(FR-093).
 * 신규 우선 노출 기간의 캠페인은 진행률에 0.8 을 곱해 앞순위로 당긴다(BR-035). 이 보정은 순서에만 쓰고 1.2 초과 제외에는 쓰지 않는다.
 */
final class AllocationOrder {

    static final double NEW_PRIORITY_FACTOR = 0.8;

    private AllocationOrder() {
    }

    static Comparator<Placed> of(FeedContext context) {
        return Comparator.<Placed>comparingDouble(p -> orderingProgress(p.candidate(), context))
                .thenComparing(p -> p.candidate().campaignCreatedAt())
                .thenComparing(p -> p.candidate().campaignId());
    }

    private static double orderingProgress(FeedCandidate candidate, FeedContext context) {
        double progress = context.pacing().get(candidate.campaignId()).progressRate();
        boolean newPriority = candidate.newPriorityUntil() != null
                && !context.businessDate().isAfter(candidate.newPriorityUntil());
        return newPriority ? progress * NEW_PRIORITY_FACTOR : progress;
    }
}
