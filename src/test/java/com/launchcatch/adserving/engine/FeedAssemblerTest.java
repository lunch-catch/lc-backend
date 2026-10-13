package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.member.contract.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("피드 배정")
class FeedAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final double LATITUDE = 37.5;
    private static final double LONGITUDE = 127.0;

    // 위도 1도는 약 111,195m 라 이 값을 곱하면 북쪽으로 대략 그 미터만큼 떨어진 위도가 된다
    private static final double DEGREE_PER_METER = 1 / 111_195.0;

    private static final Viewer VIEWER = new Viewer(LATITUDE, LONGITUDE, Gender.FEMALE,
            com.launchcatch.member.contract.AgeGroup.AGE_30S);

    private final FeedAssembler assembler = new FeedAssembler();

    private FeedPlan plan(TestInput input) {
        return assembler.assemble(input.build());
    }

    @Nested
    @DisplayName("후보를 거른다")
    class Filtering {

        @Test
        void 그날_이력에_걸린_캠페인을_뺀다() {
            FeedPlan plan = plan(input(List.of(candidate(1, 100), candidate(2, 100)), Set.of(1L)));

            assertThat(campaignIds(plan)).containsExactly(2L);
        }

        @Test
        void 필터의_업종만_남기고_빈_필터는_전체_업종이다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 100).withCategory("KOREAN"), candidate(2, 100).withCategory("JAPANESE"));

            assertThat(campaignIds(plan(input(candidates).withCategories(Set.of("KOREAN")))))
                    .containsExactly(1L);
            assertThat(campaignIds(plan(input(candidates)))).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        void 대상_성별과_연령대가_맞지_않으면_뺀다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 100).withGender(TargetGender.MALE),
                    candidate(2, 100).withAgeGroups(Set.of(AgeGroup.AGE_20S)),
                    candidate(3, 100).withGender(TargetGender.FEMALE));

            assertThat(campaignIds(plan(input(candidates)))).containsExactly(3L);
        }

        @Test
        void 성별이_OTHER_면_대상_성별이_ALL_인_캠페인만_받는다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 100).withGender(TargetGender.FEMALE), candidate(2, 100));
            Viewer other = new Viewer(LATITUDE, LONGITUDE, Gender.OTHER, VIEWER.ageGroup());

            FeedPlan plan = plan(input(candidates).withViewer(other));

            assertThat(campaignIds(plan)).containsExactly(2L);
        }

        @Test
        void 캠페인의_노출_반경_밖에_있는_사용자에게는_내려주지_않는다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 600).withTargetRadius(500), candidate(2, 600).withTargetRadius(1000));

            assertThat(campaignIds(plan(input(candidates)))).containsExactly(2L);
        }
    }

    @Nested
    @DisplayName("예산으로 거른다")
    class Budget {

        @Test
        void 예산_소진_진행률이_1_2_를_넘으면_뺀다() {
            BudgetSnapshot budget = BudgetSnapshot.of(
                    Map.of(1L, progress(1, 1.21), 2L, progress(2, 1.2)), Map.of());

            FeedPlan plan = plan(input(List.of(candidate(1, 100), candidate(2, 100))).withBudget(budget));

            assertThat(campaignIds(plan)).containsExactly(2L);
        }

        @Test
        void 유효_노출_수가_예약액_나누기_단가_곱하기_1_05_에_닿으면_뺀다() {
            // 예약액 1000 원, 단가 10 원이면 상한은 105 회다
            BudgetSnapshot budget = BudgetSnapshot.of(
                    Map.of(1L, progress(1, 0.5), 2L, progress(2, 0.5)), Map.of(1L, 105L, 2L, 104L));

            FeedPlan plan = plan(input(List.of(candidate(1, 100), candidate(2, 100))).withBudget(budget));

            assertThat(campaignIds(plan)).containsExactly(2L);
        }

        @Test
        void 그날_예산_계획이_없는_캠페인은_후보가_아니다() {
            BudgetSnapshot budget = BudgetSnapshot.of(Map.of(2L, progress(2, 0.5)), Map.of());

            FeedPlan plan = plan(input(List.of(candidate(1, 100), candidate(2, 100))).withBudget(budget));

            assertThat(campaignIds(plan)).containsExactly(2L);
        }

        @Test
        void 임시_예산을_모르면_예산_조건_없이_배정한다() {
            FeedPlan plan = plan(input(List.of(candidate(1, 100), candidate(2, 100))));

            assertThat(campaignIds(plan)).containsExactlyInAnyOrder(1L, 2L);
        }
    }

    @Nested
    @DisplayName("탐색 반경을 넓힌다")
    class SearchRadius {

        @Test
        void 반경_1000m_안에_10장이_있으면_넓히지_않는다() {
            List<TestCandidate> candidates = new ArrayList<>(manyAt(1, 10, 500));
            candidates.add(candidate(99, 1500).withTargetRadius(3000));

            FeedPlan plan = plan(input(candidates));

            assertThat(plan.radiusM()).isEqualTo(1000);
            assertThat(campaignIds(plan)).doesNotContain(99L);
        }

        @Test
        void 모자라면_10장이_되는_가장_작은_반경까지_넓힌다() {
            List<TestCandidate> candidates = new ArrayList<>(manyAt(1, 9, 500));
            candidates.add(candidate(99, 1500).withTargetRadius(2000));

            FeedPlan plan = plan(input(candidates));

            assertThat(plan.radiusM()).isEqualTo(2000);
            assertThat(plan.cards()).hasSize(10);
        }

        @Test
        void 끝까지_모자라면_가장_큰_반경을_쓴다() {
            assertThat(plan(input(List.of(candidate(1, 100)))).radiusM()).isEqualTo(5000);
            assertThat(plan(input(List.of())).radiusM()).isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("슬롯에 배정한다")
    class Slots {

        @Test
        void 배분_슬롯은_1_4_7번_자리이고_나머지는_관련성_슬롯이다() {
            FeedPlan plan = plan(input(manyAt(1, 12, 500)));

            assertThat(plan.cards()).hasSize(10);
            assertThat(plan.cards()).extracting(PlannedCard::slotPosition)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(plan.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                    .extracting(PlannedCard::slotPosition).containsExactly(1, 4, 7);
        }

        @Test
        void 배분_슬롯은_신규_우선_다음에_예산_소진_진행률이_낮은_순이다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 100), candidate(2, 100), candidate(3, 100),
                    candidate(4, 100).withNewPriorityUntil(TODAY));
            BudgetSnapshot budget = BudgetSnapshot.of(Map.of(
                    1L, progress(1, 0.9), 2L, progress(2, 0.1), 3L, progress(3, 0.5), 4L, progress(4, 1.1)), Map.of());

            FeedPlan plan = plan(input(candidates).withBudget(budget));

            assertThat(plan.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                    .extracting(card -> card.candidate().campaignId()).containsExactly(4L, 2L, 3L);
        }

        @Test
        void 신규_우선_노출이_끝난_다음_날에는_우선하지_않는다() {
            List<TestCandidate> candidates = List.of(
                    candidate(1, 100), candidate(2, 100).withNewPriorityUntil(TODAY.minusDays(1)));
            BudgetSnapshot budget = BudgetSnapshot.of(Map.of(1L, progress(1, 0.1), 2L, progress(2, 0.5)), Map.of());

            FeedPlan plan = plan(input(candidates).withBudget(budget));

            assertThat(plan.cards().getFirst().candidate().campaignId()).isEqualTo(1L);
        }

        @Test
        void 거리순이면_관련성_카드를_가까운_순으로_놓는다() {
            List<TestCandidate> candidates = new ArrayList<>(List.of(
                    candidate(1, 10), candidate(2, 20), candidate(3, 30)));
            candidates.addAll(List.of(candidate(11, 900), candidate(12, 300), candidate(13, 600)));

            FeedPlan plan = plan(input(candidates));

            assertThat(relevanceIds(plan)).containsExactly(12L, 13L, 11L);
        }

        @Test
        void 할인율순이면_관련성_카드를_할인이_큰_순으로_놓는다() {
            List<TestCandidate> candidates = new ArrayList<>(List.of(
                    candidate(1, 10), candidate(2, 20), candidate(3, 30)));
            candidates.addAll(List.of(
                    candidate(11, 300).withPercent(10), candidate(12, 600).withPercent(40),
                    candidate(13, 900).withAmount(3000)));

            FeedPlan plan = plan(input(candidates).withSort(FeedSort.DISCOUNT_RATE));

            assertThat(relevanceIds(plan)).containsExactly(12L, 13L, 11L);
        }

        @Test
        void 관련성_슬롯은_점수가_높은_7장을_고른다() {
            List<TestCandidate> candidates = new ArrayList<>(manyAt(1, 3, 10));
            candidates.addAll(manyAt(11, 7, 200));
            candidates.add(candidate(99, 950).withCategory("JAPANESE"));
            CategoryPreference preference = CategoryPreference.fromWishedCategories(List.of("KOREAN"));

            FeedPlan plan = plan(input(candidates).withPreference(preference));

            assertThat(campaignIds(plan)).doesNotContain(99L);
        }

        @Test
        void 후보가_모자라면_빈_자리_없이_당겨_채운다() {
            FeedPlan plan = plan(input(manyAt(1, 5, 100)));

            assertThat(plan.cards()).extracting(PlannedCard::slotPosition).containsExactly(1, 2, 3, 4, 5);
            assertThat(plan.cards()).extracting(PlannedCard::slotType).containsExactly(
                    SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE, SlotType.ALLOCATION, SlotType.ALLOCATION);
        }

        @Test
        void 카드의_거리는_반올림한_미터다() {
            FeedPlan plan = plan(input(List.of(candidate(1, 420))));

            assertThat(plan.cards().getFirst().distanceM()).isEqualTo(420);
        }
    }

    @Nested
    @DisplayName("관련성 점수")
    class Score {

        @Test
        void 할인율은_백분율을_금액은_1만원을_만점으로_본다() {
            assertThat(SlotAllocator.discountScore(candidate(1, 0).withPercent(20).build())).isEqualTo(0.2);
            assertThat(SlotAllocator.discountScore(candidate(1, 0).withAmount(3000).build())).isEqualTo(0.3);
            assertThat(SlotAllocator.discountScore(candidate(1, 0).withAmount(15000).build())).isEqualTo(1.0);
        }

        @Test
        void 거리_45_카테고리_35_할인_20_으로_더한다() {
            FeedCandidate candidate = candidate(1, 0).withPercent(50).build();
            CategoryPreference preference = CategoryPreference.fromWishedCategories(List.of("KOREAN", "JAPANESE"));

            double score = SlotAllocator.relevanceScore(new FeedAssembler.Placed(candidate, 500), 1000, preference);

            assertThat(score).isCloseTo(0.45 * 0.5 + 0.35 * 0.5 + 0.20 * 0.5, offset(1e-9));
        }
    }

    private static List<Long> campaignIds(FeedPlan plan) {
        return plan.cards().stream().map(card -> card.candidate().campaignId()).toList();
    }

    private static List<Long> relevanceIds(FeedPlan plan) {
        return plan.cards().stream()
                .filter(card -> card.slotType() == SlotType.RELEVANCE)
                .map(card -> card.candidate().campaignId())
                .toList();
    }

    private static BudgetProgress progress(long campaignId, double rate) {
        return new BudgetProgress(campaignId, 1000, 10, 0, 0, rate);
    }

    private static List<TestCandidate> manyAt(long firstId, int count, int metersNorth) {
        return LongStream.range(firstId, firstId + count).mapToObj(id -> candidate(id, metersNorth)).toList();
    }

    private static TestInput input(List<TestCandidate> candidates) {
        return input(candidates, Set.of());
    }

    private static TestInput input(List<TestCandidate> candidates, Set<Long> excluded) {
        return new TestInput(VIEWER, candidates, Set.of(), FeedSort.DISTANCE, excluded,
                CategoryPreference.NONE, BudgetSnapshot.UNKNOWN);
    }

    private static TestCandidate candidate(long campaignId, int metersNorth) {
        return new TestCandidate(campaignId, LATITUDE + metersNorth * DEGREE_PER_METER, "KOREAN", 1000,
                TargetGender.ALL, Set.of(AgeGroup.AGE_30S), DiscountType.PERCENT, 20, null);
    }

    // 테스트에서 바꾸는 값만 열어 둔 후보 픽스처다
    private record TestCandidate(long campaignId, double latitude, String category, int targetRadius,
                                 TargetGender gender, Set<AgeGroup> ageGroups, DiscountType discountType,
                                 int discountValue, LocalDate newPriorityUntil) {

        TestCandidate withCategory(String value) {
            return new TestCandidate(campaignId, latitude, value, targetRadius, gender, ageGroups, discountType,
                    discountValue, newPriorityUntil);
        }

        TestCandidate withTargetRadius(int value) {
            return new TestCandidate(campaignId, latitude, category, value, gender, ageGroups, discountType,
                    discountValue, newPriorityUntil);
        }

        TestCandidate withGender(TargetGender value) {
            return new TestCandidate(campaignId, latitude, category, targetRadius, value, ageGroups, discountType,
                    discountValue, newPriorityUntil);
        }

        TestCandidate withAgeGroups(Set<AgeGroup> value) {
            return new TestCandidate(campaignId, latitude, category, targetRadius, gender, value, discountType,
                    discountValue, newPriorityUntil);
        }

        TestCandidate withPercent(int value) {
            return new TestCandidate(campaignId, latitude, category, targetRadius, gender, ageGroups,
                    DiscountType.PERCENT, value, newPriorityUntil);
        }

        TestCandidate withAmount(int value) {
            return new TestCandidate(campaignId, latitude, category, targetRadius, gender, ageGroups,
                    DiscountType.AMOUNT, value, newPriorityUntil);
        }

        TestCandidate withNewPriorityUntil(LocalDate value) {
            return new TestCandidate(campaignId, latitude, category, targetRadius, gender, ageGroups, discountType,
                    discountValue, value);
        }

        FeedCandidate build() {
            return new FeedCandidate(campaignId, campaignId + 1000, "가게" + campaignId, category, latitude, LONGITUDE,
                    targetRadius, gender, ageGroups, DiscountTargetType.ALL, discountType, discountValue,
                    LocalTime.of(12, 0), LocalTime.of(14, 0), 30,
                    LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(campaignId), newPriorityUntil);
        }
    }

    private record TestInput(Viewer viewer, List<TestCandidate> candidates, Set<String> categories, FeedSort sort,
                             Set<Long> excluded, CategoryPreference preference, BudgetSnapshot budget) {

        TestInput withCategories(Set<String> value) {
            return new TestInput(viewer, candidates, value, sort, excluded, preference, budget);
        }

        TestInput withViewer(Viewer value) {
            return new TestInput(value, candidates, categories, sort, excluded, preference, budget);
        }

        TestInput withSort(FeedSort value) {
            return new TestInput(viewer, candidates, categories, value, excluded, preference, budget);
        }

        TestInput withPreference(CategoryPreference value) {
            return new TestInput(viewer, candidates, categories, sort, excluded, value, budget);
        }

        TestInput withBudget(BudgetSnapshot value) {
            return new TestInput(viewer, candidates, categories, sort, excluded, preference, value);
        }

        FeedInput build() {
            return new FeedInput(viewer, TODAY, candidates.stream().map(TestCandidate::build).toList(),
                    categories, sort, excluded, preference, budget);
        }
    }
}
