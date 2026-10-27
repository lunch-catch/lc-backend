package com.lunchcatch.adserving.engine.entity;

import static com.lunchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("그날 후보")
class AdCandidateTest {

    // 연령대에 중복이 있어도 피드 후보로 바꾼다. Set.of 는 중복에 예외를 던져 그 가게 주변의 피드가 모두 실패했다(#783)
    @Test
    void 연령대에_중복이_있어도_하나로_모아_피드_후보로_바꾼다() {
        AdCandidate duplicated = candidate(101, 37.4979, 127.0276).set("targetAgeGroups", "AGE_20S,AGE_20S,AGE_30S").build();

        assertThat(duplicated.toFeedCandidate().targetAgeGroups()).containsExactlyInAnyOrder("AGE_20S", "AGE_30S");
    }
}
