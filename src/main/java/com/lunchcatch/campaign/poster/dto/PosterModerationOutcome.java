package com.lunchcatch.campaign.poster.dto;

import com.lunchcatch.campaign.poster.entity.ModerationFailReason;
import com.lunchcatch.campaign.poster.entity.ModerationResult;
import java.util.List;

/*
 * 포스터 자동 검수 결과다.
 * PASS 면 reasons 가 비어 있고, FAIL 이면 걸린 사유를 모두 담는다.
 */
public record PosterModerationOutcome(ModerationResult result, List<ModerationFailReason> reasons) {

    public PosterModerationOutcome {
        reasons = List.copyOf(reasons);
    }
}
