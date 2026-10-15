package com.launchcatch.adserving.engine;

import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.adserving.engine.FeedAssembler.Selection;
import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import com.launchcatch.adserving.engine.entity.TargetGender;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("피드 카드 고르기")
class FeedAssemblerTest {

    private static final double LAT = 37.5;
    private static final double LNG = 127.0;
    // 위도 1도가 약 111,195m 라 0.001도가 약 111m 다
    private static final double DEG_PER_M = 1 / 111_195.0;

    private static final Viewer VIEWER = new Viewer(LAT, LNG, Gender.FEMALE, AgeGroup.AGE_30S);

    private static FeedCandidate northBy(long campaignId, double meters) {
        return candidate(campaignId, LAT + meters * DEG_PER_M, LNG).build().toFeedCandidate();
    }

    private static List<Long> campaignIds(Selection selection) {
        return selection.cards().stream().map(p -> p.candidate().campaignId()).toList();
    }

    private static Selection assemble(List<FeedCandidate> candidates) {
        return FeedAssembler.assemble(candidates, VIEWER, Set.of(), Set.of());
    }

    @Test
    void 반경_1000m_안에_10장이_있으면_1000m_에서_가까운_순으로_10장을_고른다() {
        List<FeedCandidate> candidates = LongStream.rangeClosed(1, 12)
                .mapToObj(id -> northBy(id, 1000 - id * 50)).toList();

        Selection selection = assemble(candidates);

        assertThat(selection.radiusM()).isEqualTo(1000);
        assertThat(campaignIds(selection)).containsExactly(12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L);
    }

    @Test
    void 반경_안의_후보가_10장보다_적으면_10장이_모이는_반경까지_넓힌다() {
        List<FeedCandidate> candidates = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> northBy(id, id <= 5 ? 500 : 1500)).toList();

        assertThat(assemble(candidates).radiusM()).isEqualTo(2000);
    }

    @Test
    void 최대_반경에서도_10장이_안_되면_있는_만큼_5000m_로_돌려준다() {
        Selection selection = assemble(List.of(northBy(1, 2500), northBy(2, 400)));

        assertThat(selection.radiusM()).isEqualTo(5000);
        assertThat(campaignIds(selection)).containsExactly(2L, 1L);
    }

    @Test
    void 후보가_하나도_없으면_빈_카드와_5000m_다() {
        Selection selection = assemble(List.of());

        assertThat(selection.radiusM()).isEqualTo(5000);
        assertThat(selection.cards()).isEmpty();
    }

    @Test
    void 거리가_같으면_캠페인_ID_오름차순이다() {
        Selection selection = assemble(List.of(northBy(9, 300), northBy(3, 300)));

        assertThat(campaignIds(selection)).containsExactly(3L, 9L);
    }

    @Test
    void 캠페인이_지정한_노출_반경_밖의_후보는_뺀다() {
        FeedCandidate outside = candidate(1, LAT + 600 * DEG_PER_M, LNG).set("targetRadius", (short) 500).build().toFeedCandidate();

        assertThat(campaignIds(assemble(List.of(outside, northBy(2, 600))))).containsExactly(2L);
    }

    @ParameterizedTest
    @CsvSource({"ALL, true", "FEMALE, true", "MALE, false"})
    void 노출_대상_성별이_맞는_후보만_고른다(TargetGender target, boolean included) {
        FeedCandidate c = candidate(1, LAT, LNG).set("targetGender", target).build().toFeedCandidate();

        assertThat(assemble(List.of(c)).cards()).hasSize(included ? 1 : 0);
    }

    @Test
    void 성별이_기타인_사용자는_전체_성별_후보만_받는다() {
        Viewer other = new Viewer(LAT, LNG, Gender.OTHER, AgeGroup.AGE_30S);
        FeedCandidate all = candidate(1, LAT, LNG).build().toFeedCandidate();
        FeedCandidate female = candidate(2, LAT, LNG).set("targetGender", TargetGender.FEMALE).build().toFeedCandidate();

        Selection selection = FeedAssembler.assemble(List.of(all, female), other, Set.of(), Set.of());

        assertThat(campaignIds(selection)).containsExactly(1L);
    }

    @Test
    void 노출_대상_연령대에_없는_사용자에게는_고르지_않는다() {
        FeedCandidate c = candidate(1, LAT, LNG).set("targetAgeGroups", "AGE_20S,AGE_40S").build().toFeedCandidate();

        assertThat(assemble(List.of(c)).cards()).isEmpty();
    }

    @Test
    void 당일_이력에_있는_캠페인은_뺀다() {
        Selection selection = FeedAssembler.assemble(
                List.of(northBy(1, 100), northBy(2, 200)), VIEWER, Set.of(1L), Set.of());

        assertThat(campaignIds(selection)).containsExactly(2L);
    }

    @Test
    void 필터에서_고른_업종만_고르고_고른_업종이_없으면_전부_고른다() {
        FeedCandidate korean = northBy(1, 100);
        FeedCandidate japanese = candidate(2, LAT, LNG).set("categoryCode", "JAPANESE").build().toFeedCandidate();

        assertThat(campaignIds(FeedAssembler.assemble(List.of(korean, japanese), VIEWER, Set.of(), Set.of("JAPANESE"))))
                .containsExactly(2L);
        assertThat(campaignIds(FeedAssembler.assemble(List.of(korean, japanese), VIEWER, Set.of(), Set.of())))
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void 거리는_미터_단위로_반올림한다() {
        Placed placed = assemble(List.of(northBy(1, 420.4))).cards().getFirst();

        assertThat(placed.distanceM()).isEqualTo(420);
    }
}
