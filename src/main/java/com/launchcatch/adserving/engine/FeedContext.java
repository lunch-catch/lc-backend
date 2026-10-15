package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import java.util.Map;
import java.util.Set;

/*
 * 한 사용자에게 카드를 고를 때 후보 말고 필요한 값이다. service 가 저장소와 다른 도메인에서 읽어 넘긴다.
 * pacing 이 null 이면 정산에서 예산을 읽지 못한 것이다. 그때는 예산 규칙을 건너뛰고 배분 슬롯도 관련성으로 채운다(#307).
 */
public record FeedContext(
        Viewer viewer,
        Set<Long> excludedCampaignIds,
        Set<String> categories,
        FeedSort sort,
        Map<String, Double> categoryPreference,
        Map<Long, Integer> dailyServedCounts,
        Set<Long> sessionServedCampaignIds,
        Map<Long, CampaignPacing> pacing
) {

    public boolean pacingAvailable() {
        return pacing != null;
    }
}
