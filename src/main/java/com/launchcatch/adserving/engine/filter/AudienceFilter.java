package com.launchcatch.adserving.engine.filter;

import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;
import com.launchcatch.adserving.engine.entity.TargetGender;
import com.launchcatch.member.contract.Gender;

// 캠페인의 노출 대상 성별과 연령대에 맞는 사용자에게만 내보낸다
public final class AudienceFilter implements CandidateFilter {

    @Override
    public boolean accepts(FeedCandidate candidate, FeedContext context) {
        Viewer viewer = context.viewer();
        return targetsGender(candidate.targetGender(), viewer.gender())
                && viewer.ageGroup() != null && candidate.targetAgeGroups().contains(viewer.ageGroup().name());
    }

    private static boolean targetsGender(TargetGender target, Gender gender) {
        return target == TargetGender.ALL || (gender != null && target.name().equals(gender.name()));
    }
}
