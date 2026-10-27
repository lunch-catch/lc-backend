package com.lunchcatch.adserving.engine;

import static com.lunchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.adserving.engine.FeedAssembler.Placed;
import com.lunchcatch.adserving.engine.FeedAssembler.Selection;
import com.lunchcatch.adserving.engine.FeedAssembler.Viewer;
import com.lunchcatch.adserving.engine.entity.DiscountType;
import com.lunchcatch.adserving.engine.entity.TargetGender;
import com.lunchcatch.member.contract.AgeGroup;
import com.lunchcatch.member.contract.Gender;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    // 후보마다 진행률 0.5 이고 상한에 닿지 않은 예산을 둔다
    private static Map<Long, CampaignPacing> pacingFor(List<FeedCandidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(FeedCandidate::campaignId,
                c -> new CampaignPacing(0.5, 100_000, 10, 0)));
    }

    private static FeedContext context(List<FeedCandidate> candidates) {
        return new Builder(candidates).build();
    }

    private static Selection assemble(List<FeedCandidate> candidates) {
        return FeedAssembler.assemble(candidates, context(candidates));
    }

    private static List<Long> campaignIds(Selection selection) {
        return selection.cards().stream().map(p -> p.candidate().campaignId()).toList();
    }

    private static final class Builder {
        private Set<Long> excluded = Set.of();
        private Set<String> categories = Set.of();
        private FeedSort sort = FeedSort.DISTANCE;
        private Map<String, Double> preference = Map.of();
        private Map<Long, Integer> daily = Map.of();
        private Set<Long> session = Set.of();
        private Map<Long, CampaignPacing> pacing;
        private Viewer viewer = VIEWER;

        Builder(List<FeedCandidate> candidates) {
            pacing = pacingFor(candidates);
        }

        FeedContext build() {
            return new FeedContext(AdCandidateFixture.BUSINESS_DATE, viewer, excluded, categories, sort, preference, daily, session, pacing);
        }
    }

    @Nested
    @DisplayName("후보 조건")
    class Eligibility {

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
            FeedCandidate all = candidate(1, LAT, LNG).build().toFeedCandidate();
            FeedCandidate female = candidate(2, LAT, LNG).set("targetGender", TargetGender.FEMALE).build().toFeedCandidate();
            Builder b = new Builder(List.of(all, female));
            b.viewer = new Viewer(LAT, LNG, Gender.OTHER, AgeGroup.AGE_30S);

            assertThat(campaignIds(FeedAssembler.assemble(List.of(all, female), b.build()))).containsExactly(1L);
        }

        @Test
        void 노출_대상_연령대에_없는_사용자에게는_고르지_않는다() {
            FeedCandidate c = candidate(1, LAT, LNG).set("targetAgeGroups", "AGE_20S,AGE_40S").build().toFeedCandidate();

            assertThat(assemble(List.of(c)).cards()).isEmpty();
        }

        @Test
        void 당일_이력에_있는_캠페인은_뺀다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            b.excluded = Set.of(1L);

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(2L);
        }

        @Test
        void 필터에서_고른_업종만_고르고_고른_업종이_없으면_전부_고른다() {
            FeedCandidate korean = northBy(1, 100);
            FeedCandidate japanese = candidate(2, LAT, LNG).set("categoryCode", "JAPANESE").build().toFeedCandidate();
            Builder b = new Builder(List.of(korean, japanese));
            b.categories = Set.of("JAPANESE");

            assertThat(campaignIds(FeedAssembler.assemble(List.of(korean, japanese), b.build()))).containsExactly(2L);
            assertThat(campaignIds(assemble(List.of(korean, japanese)))).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        void 오늘_두_번_내려준_캠페인은_뺀다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            b.daily = Map.of(1L, 2, 2L, 1);

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(2L);
        }

        @Test
        void 이번_세션에_내려준_캠페인은_뺀다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            b.session = Set.of(2L);

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(1L);
        }

        @Test
        void 진행률이_1_2_를_넘으면_빼고_1_2_는_남긴다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            b.pacing = Map.of(1L, new CampaignPacing(1.2001, 100_000, 10, 0), 2L, new CampaignPacing(1.2, 100_000, 10, 0));

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(2L);
        }

        @Test
        void 유효_노출_수가_예약액_나누기_단가의_1_05_배에_닿으면_뺀다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            // 100,000 / 10 x 1.05 = 10,500
            b.pacing = Map.of(1L, new CampaignPacing(0.5, 100_000, 10, 10_500), 2L, new CampaignPacing(0.5, 100_000, 10, 10_499));

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(2L);
        }

        @Test
        void 예산_계획이_없는_캠페인은_뺀다() {
            List<FeedCandidate> candidates = List.of(northBy(1, 100), northBy(2, 200));
            Builder b = new Builder(candidates);
            b.pacing = Map.of(2L, new CampaignPacing(0.5, 100_000, 10, 0));

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).containsExactly(2L);
        }
    }

    @Nested
    @DisplayName("탐색 반경")
    class SearchRadius {

        @Test
        void 반경_1000m_안에_10장이_있으면_1000m_다() {
            List<FeedCandidate> candidates = LongStream.rangeClosed(1, 12)
                    .mapToObj(id -> northBy(id, 1000 - id * 50)).toList();

            assertThat(assemble(candidates).radiusM()).isEqualTo(1000);
            assertThat(assemble(candidates).cards()).hasSize(10);
        }

        @Test
        void 반경_안의_후보가_10장보다_적으면_10장이_모이는_반경까지_넓힌다() {
            List<FeedCandidate> candidates = LongStream.rangeClosed(1, 10)
                    .mapToObj(id -> northBy(id, id <= 5 ? 500 : 1500)).toList();

            assertThat(assemble(candidates).radiusM()).isEqualTo(2000);
        }

        @Test
        void 후보가_하나도_없으면_빈_카드와_5000m_다() {
            Selection selection = assemble(List.of());

            assertThat(selection.radiusM()).isEqualTo(5000);
            assertThat(selection.cards()).isEmpty();
        }

        @Test
        void 거리는_미터_단위로_반올림한다() {
            Placed placed = assemble(List.of(northBy(1, 420.4))).cards().getFirst();

            assertThat(placed.distanceM()).isEqualTo(420);
        }
    }

    @Nested
    @DisplayName("슬롯 배치")
    class Slots {

        private final List<FeedCandidate> twelve = LongStream.rangeClosed(1, 12)
                .mapToObj(id -> northBy(id, id * 50)).toList();

        private Map<Long, CampaignPacing> progress(Map<Long, Double> rates) {
            Map<Long, CampaignPacing> pacing = new HashMap<>(pacingFor(twelve));
            rates.forEach((id, rate) -> pacing.put(id, new CampaignPacing(rate, 100_000, 10, 0)));
            return pacing;
        }

        @Test
        void 진행률이_낮은_세_캠페인을_1_4_7_번에_두고_나머지는_관련성이다() {
            Builder b = new Builder(twelve);
            b.pacing = progress(Map.of(12L, 0.1, 11L, 0.2, 10L, 0.3));

            List<Placed> cards = FeedAssembler.assemble(twelve, b.build()).cards();

            assertThat(cards).hasSize(10);
            assertThat(List.of(cards.get(0), cards.get(3), cards.get(6)))
                    .extracting(p -> p.candidate().campaignId()).containsExactly(12L, 11L, 10L);
            assertThat(cards).filteredOn(p -> SlotLabels.ALLOCATION.equals(p.slotType())).hasSize(3);
            assertThat(List.of(cards.get(0), cards.get(3), cards.get(6)))
                    .allMatch(p -> SlotLabels.ALLOCATION.equals(p.slotType()));
            assertThat(cards).extracting(p -> p.candidate().campaignId()).doesNotHaveDuplicates();
        }

        @Test
        void 진행률이_같으면_먼저_만든_캠페인을_배분에_둔다() {
            FeedCandidate older = candidate(9, LAT, LNG).set("campaignCreatedAt", LocalDateTime.of(2026, 9, 1, 0, 0)).build().toFeedCandidate();
            FeedCandidate newer = candidate(3, LAT, LNG).set("campaignCreatedAt", LocalDateTime.of(2026, 9, 2, 0, 0)).build().toFeedCandidate();
            List<FeedCandidate> candidates = List.of(newer, older);
            Builder b = new Builder(candidates);

            List<Placed> cards = FeedAssembler.assemble(candidates, b.build()).cards();

            assertThat(cards.getFirst().candidate().campaignId()).isEqualTo(9L);
        }

        @Test
        void 신규_우선_노출_기간의_캠페인은_진행률에_0_8을_곱해_배분에_앞선다() {
            // 1 은 진행률 0.5 지만 신규 우선이라 0.4 로 보고, 0.45 인 2 보다 앞선다
            FeedCandidate fresh = candidate(1, LAT, LNG)
                    .set("newPriorityUntil", AdCandidateFixture.BUSINESS_DATE).build().toFeedCandidate();
            FeedCandidate plain = candidate(2, LAT, LNG).build().toFeedCandidate();
            List<FeedCandidate> candidates = List.of(plain, fresh);
            Builder b = new Builder(candidates);
            b.pacing = Map.of(1L, new CampaignPacing(0.5, 100_000, 10, 0), 2L, new CampaignPacing(0.45, 100_000, 10, 0));

            List<Placed> cards = FeedAssembler.assemble(candidates, b.build()).cards();

            assertThat(cards.getFirst().candidate().campaignId()).isEqualTo(1L);
        }

        @Test
        void 신규_우선_노출_기간이_지나면_진행률을_그대로_쓴다() {
            FeedCandidate expired = candidate(1, LAT, LNG)
                    .set("newPriorityUntil", AdCandidateFixture.BUSINESS_DATE.minusDays(1)).build().toFeedCandidate();
            FeedCandidate plain = candidate(2, LAT, LNG).build().toFeedCandidate();
            List<FeedCandidate> candidates = List.of(expired, plain);
            Builder b = new Builder(candidates);
            b.pacing = Map.of(1L, new CampaignPacing(0.5, 100_000, 10, 0), 2L, new CampaignPacing(0.45, 100_000, 10, 0));

            List<Placed> cards = FeedAssembler.assemble(candidates, b.build()).cards();

            assertThat(cards.getFirst().candidate().campaignId()).isEqualTo(2L);
        }

        @Test
        void 카드마다_배정_때의_진행률과_관련성_점수를_싣는다() {
            Builder b = new Builder(twelve);
            b.pacing = progress(Map.of(12L, 0.1));

            List<Placed> cards = FeedAssembler.assemble(twelve, b.build()).cards();

            Placed first = cards.getFirst();
            assertThat(first.candidate().campaignId()).isEqualTo(12L);
            assertThat(first.progressRate()).isEqualTo(0.1);
            assertThat(cards).allSatisfy(p -> assertThat(p.relevanceScore()).isBetween(0.0, 1.0).isPositive());
        }

        @Test
        void 예산을_읽지_못했으면_진행률을_비워_싣는다() {
            Builder b = new Builder(twelve);
            b.pacing = null;

            assertThat(FeedAssembler.assemble(twelve, b.build()).cards()).allSatisfy(p -> assertThat(p.progressRate()).isNull());
        }

        @Test
        void 정산을_읽지_못했으면_예산_규칙_없이_모두_관련성_슬롯이다() {
            Builder b = new Builder(twelve);
            b.pacing = null;

            List<Placed> cards = FeedAssembler.assemble(twelve, b.build()).cards();

            assertThat(cards).hasSize(10).allMatch(p -> SlotLabels.RELEVANCE.equals(p.slotType()));
        }

        @Test
        void 전체가_10장보다_적으면_빈_자리_없이_앞으로_당긴다() {
            List<FeedCandidate> two = List.of(northBy(1, 100), northBy(2, 200));

            List<Placed> cards = assemble(two).cards();

            assertThat(cards).hasSize(2);
            assertThat(cards).extracting(Placed::slotType).containsExactly(SlotLabels.ALLOCATION, SlotLabels.ALLOCATION);
        }

        @Test
        void 카드가_10장보다_적으면_배분_카드가_비는_자리를_채운다() {
            List<FeedCandidate> five = LongStream.rangeClosed(1, 5).mapToObj(id -> northBy(id, id * 50)).toList();

            List<Placed> cards = assemble(five).cards();

            assertThat(cards).extracting(Placed::slotType).containsExactly(
                    SlotLabels.ALLOCATION, SlotLabels.RELEVANCE, SlotLabels.RELEVANCE, SlotLabels.ALLOCATION, SlotLabels.ALLOCATION);
        }
    }

    @Nested
    @DisplayName("관련성 점수와 필터 정렬")
    class Relevance {

        @Test
        void 점수는_거리_45_카테고리_35_할인_20_이다() {
            FeedCandidate c = northBy(1, 500);
            Placed p = new Placed(c, 500, SlotLabels.RELEVANCE);
            Builder b = new Builder(List.of(c));
            b.preference = Map.of("KOREAN", 0.5);

            double score = FeedAssembler.relevanceScore(p, b.build(), 1000, Map.of(DiscountType.PERCENT, 40));

            // 0.45 x (1 - 500/1000) + 0.35 x 0.5 + 0.20 x (20/40)
            assertThat(score).isCloseTo(0.45 * 0.5 + 0.35 * 0.5 + 0.20 * 0.5, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        void 할인_점수는_같은_종류의_최댓값으로_나눈다() {
            FeedCandidate amount = candidate(1, LAT, LNG).set("discountType", DiscountType.AMOUNT).set("discountValue", 1500).build().toFeedCandidate();

            assertThat(FeedAssembler.discountScore(amount, Map.of(DiscountType.AMOUNT, 3000, DiscountType.PERCENT, 50)))
                    .isEqualTo(0.5);
        }

        @Test
        void 선호_업종의_후보가_가까운_후보보다_관련성_슬롯에_먼저_뽑힌다() {
            // 배분 3장을 진행률로 따로 고정하고 관련성 7자리를 두고 겨루게 한다
            List<FeedCandidate> candidates = new java.util.ArrayList<>(LongStream.rangeClosed(1, 10)
                    .mapToObj(id -> northBy(id, 100 + id)).toList());
            FeedCandidate farJapanese = candidate(99, LAT + 500 * DEG_PER_M, LNG).set("categoryCode", "JAPANESE").build().toFeedCandidate();
            candidates.add(farJapanese);
            Builder b = new Builder(candidates);
            b.preference = Map.of("JAPANESE", 1.0);

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build()))).contains(99L);
            assertThat(campaignIds(assemble(candidates))).doesNotContain(99L);
        }

        @Test
        void 할인순_필터면_관련성_슬롯을_할인_점수_내림차순으로_줄_세운다() {
            Function<Long, FeedCandidate> withDiscount = id -> candidate(id, LAT + id * DEG_PER_M, LNG)
                    .set("discountValue", (int) (id * 5)).build().toFeedCandidate();
            List<FeedCandidate> candidates = LongStream.rangeClosed(1, 10).mapToObj(withDiscount::apply).toList();
            Builder b = new Builder(candidates);
            b.pacing = null;
            b.sort = FeedSort.DISCOUNT_RATE;

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build())))
                    .containsExactly(10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L, 1L);
        }

        @Test
        void 거리순_필터면_관련성_슬롯을_가까운_순으로_줄_세운다() {
            Function<Long, FeedCandidate> withDiscount = id -> candidate(id, LAT + id * DEG_PER_M, LNG)
                    .set("discountValue", (int) (id * 5)).build().toFeedCandidate();
            List<FeedCandidate> candidates = LongStream.rangeClosed(1, 10).mapToObj(withDiscount::apply).toList();
            Builder b = new Builder(candidates);
            b.pacing = null;

            assertThat(campaignIds(FeedAssembler.assemble(candidates, b.build())))
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        }
    }
}
