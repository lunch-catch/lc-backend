package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeedAssemblerTest {

    private static final double USER_LAT = 37.5;
    private static final double USER_LNG = 127.0;
    // 지구 평균 반지름 기준 위도 1도의 길이(미터)다
    private static final double METERS_PER_LAT_DEGREE = 111_195.08;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 9, 0);

    @Nested
    class 자격 {

        @Test
        void 사용자가_캠페인의_노출_반경_밖이면_뺀다() {
            List<NearbyCandidate> result = eligible(List.of(
                    candidate(1L, 900).targetRadius(1000).build(),
                    candidate(2L, 1100).targetRadius(1000).build()));

            assertThat(result).extracting(NearbyCandidate::campaignId).containsExactly(1L);
        }

        @Test
        void 노출_반경과_같은_거리는_안으로_센다() {
            List<NearbyCandidate> result = eligible(List.of(candidate(1L, 500).targetRadius(500).build()));

            assertThat(result).hasSize(1);
        }

        @Test
        void 거리를_미터로_반올림해_잰다() {
            List<NearbyCandidate> result = eligible(List.of(candidate(1L, 420).build()));

            assertThat(result.getFirst().distanceM()).isEqualTo(420);
        }

        @ParameterizedTest
        @CsvSource({"ALL, MALE, true", "MALE, MALE, true", "FEMALE, MALE, false", "ALL, OTHER, true", "MALE, OTHER, false"})
        void 대상_성별이_맞아야_남긴다(String targetGender, Gender memberGender, boolean kept) {
            List<NearbyCandidate> result = FeedAssembler.eligible(
                    List.of(candidate(1L, 100).targetGender(targetGender).build()),
                    USER_LAT, USER_LNG, memberGender, AgeGroup.AGE_30S, FeedFilter.DEFAULT, Set.of());

            assertThat(result.isEmpty()).isEqualTo(!kept);
        }

        @Test
        void 대상_연령대에_사용자_연령대가_없으면_뺀다() {
            List<NearbyCandidate> result = FeedAssembler.eligible(
                    List.of(candidate(1L, 100).targetAgeGroups(Set.of("AGE_20S", "AGE_40S")).build(),
                            candidate(2L, 100).targetAgeGroups(Set.of("AGE_30S")).build()),
                    USER_LAT, USER_LNG, Gender.MALE, AgeGroup.AGE_30S, FeedFilter.DEFAULT, Set.of());

            assertThat(result).extracting(NearbyCandidate::campaignId).containsExactly(2L);
        }

        @Test
        void 업종_필터에_없는_업종은_뺀다() {
            FeedFilter filter = new FeedFilter(Set.of("KOREAN"), FeedSort.DISTANCE);

            List<NearbyCandidate> result = FeedAssembler.eligible(
                    List.of(candidate(1L, 100).category("KOREAN").build(),
                            candidate(2L, 100).category("JAPANESE").build()),
                    USER_LAT, USER_LNG, Gender.MALE, AgeGroup.AGE_30S, filter, Set.of());

            assertThat(result).extracting(NearbyCandidate::campaignId).containsExactly(1L);
        }

        @Test
        void 업종_필터가_비어_있으면_전체_업종이다() {
            List<NearbyCandidate> result = eligible(List.of(
                    candidate(1L, 100).category("KOREAN").build(),
                    candidate(2L, 100).category("JAPANESE").build()));

            assertThat(result).hasSize(2);
        }

        @Test
        void 제외할_캠페인은_뺀다() {
            List<NearbyCandidate> result = FeedAssembler.eligible(
                    List.of(candidate(1L, 100).build(), candidate(2L, 100).build()),
                    USER_LAT, USER_LNG, Gender.MALE, AgeGroup.AGE_30S, FeedFilter.DEFAULT, Set.of(2L));

            assertThat(result).extracting(NearbyCandidate::campaignId).containsExactly(1L);
        }

        private List<NearbyCandidate> eligible(List<ServableCandidate> candidates) {
            return FeedAssembler.eligible(candidates, USER_LAT, USER_LNG, Gender.MALE, AgeGroup.AGE_30S,
                    FeedFilter.DEFAULT, Set.of());
        }
    }

    @Nested
    class 예산 {

        @Test
        void 예산_소진_진행률이_1_2를_넘으면_뺀다() {
            List<NearbyCandidate> nearby = List.of(near(1L, 100), near(2L, 100));
            Map<Long, BudgetProgress> progress = Map.of(1L, progress(1L, 1.2), 2L, progress(2L, 1.2001));

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(campaignIds(result)).containsExactly(1L);
        }

        @Test
        void 그날_유효_노출이_예약액을_단가로_나눈_값의_1_05배에_닿으면_뺀다() {
            // 예약액 10,000원, 단가 10원이면 상한은 1,050회다
            List<NearbyCandidate> nearby = List.of(near(1L, 100), near(2L, 100));
            Map<Long, BudgetProgress> progress = Map.of(
                    1L, new BudgetProgress(1L, 10_000, 10, 0, 0, 0.5),
                    2L, new BudgetProgress(2L, 10_000, 10, 0, 0, 0.5));

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(1L, 1_049L, 2L, 1_050L),
                    Map.of(), FeedSort.DISTANCE);

            assertThat(campaignIds(result)).containsExactly(1L);
        }

        @Test
        void 그날_예산_계획이_없는_후보는_뺀다() {
            List<NearbyCandidate> nearby = List.of(near(1L, 100), near(2L, 100));

            FeedAssembly result = FeedAssembler.assemble(nearby, Map.of(1L, progress(1L, 0.5)), Map.of(), Map.of(),
                    FeedSort.DISTANCE);

            assertThat(campaignIds(result)).containsExactly(1L);
        }
    }

    @Nested
    class 슬롯 {

        @Test
        void 배분_3장은_1_4_7번에_예산_소진_진행률이_낮은_순으로_놓는다() {
            List<NearbyCandidate> nearby = new ArrayList<>();
            Map<Long, BudgetProgress> progress = new HashMap<>();
            // 1~3번은 진행률이 낮고 멀다. 관련성으로는 뽑히지 않을 만큼 멀게 둔다
            double[] rates = {0.3, 0.1, 0.2};
            for (long id = 1; id <= 3; id++) {
                nearby.add(near(id, 900));
                progress.put(id, progress(id, rates[(int) id - 1]));
            }
            for (long id = 11; id <= 17; id++) {
                nearby.add(near(id, (int) id * 10));
                progress.put(id, progress(id, 0.9));
            }

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(result.cards()).hasSize(10);
            assertThat(result.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                    .extracting(SlotCard::slotPosition, card -> card.nearby().campaignId())
                    .containsExactly(
                            tuple(1, 2L),
                            tuple(4, 3L),
                            tuple(7, 1L));
        }

        @Test
        void 진행률이_같으면_먼저_만든_캠페인이_배분_슬롯에_앞선다() {
            NearbyCandidate older = new NearbyCandidate(candidate(1L, 100).createdAt(CREATED).build(), 100);
            NearbyCandidate newer = new NearbyCandidate(candidate(2L, 100).createdAt(CREATED.plusDays(1)).build(), 100);

            FeedAssembly result = FeedAssembler.assemble(List.of(newer, older),
                    Map.of(1L, progress(1L, 0.5), 2L, progress(2L, 0.5)), Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(campaignIds(result)).containsExactly(1L, 2L);
        }

        @Test
        void 카드는_10장을_넘지_않는다() {
            List<NearbyCandidate> nearby = new ArrayList<>();
            Map<Long, BudgetProgress> progress = new HashMap<>();
            for (long id = 1; id <= 15; id++) {
                nearby.add(near(id, (int) id * 10));
                progress.put(id, progress(id, 0.5));
            }

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(result.cards()).hasSize(10);
            assertThat(result.cards()).extracting(SlotCard::slotPosition)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        @Test
        void 카드가_10장보다_적으면_빈_위치_없이_앞으로_당긴다() {
            FeedAssembly result = FeedAssembler.assemble(List.of(near(1L, 100), near(2L, 200)),
                    Map.of(1L, progress(1L, 0.5), 2L, progress(2L, 0.5)), Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(result.cards()).extracting(SlotCard::slotPosition, SlotCard::slotType)
                    .containsExactly(
                            tuple(1, SlotType.ALLOCATION),
                            tuple(2, SlotType.ALLOCATION));
        }

        @Test
        void 배분이_모자라면_관련성_카드가_그_위치를_채운다() {
            List<NearbyCandidate> nearby = new ArrayList<>();
            Map<Long, BudgetProgress> progress = new HashMap<>();
            for (long id = 1; id <= 5; id++) {
                nearby.add(near(id, (int) id * 10));
                progress.put(id, progress(id, 0.5));
            }

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(result.cards()).extracting(SlotCard::slotType).containsExactly(
                    SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE, SlotType.ALLOCATION,
                    SlotType.ALLOCATION);
        }
    }

    @Nested
    class 관련성 {

        @Test
        void 관련성_7장은_거리_45_선호도_35_할인_20의_점수로_고른다() {
            /*
             * 배분 3장은 진행률이 가장 낮은 1~3번이 가져간다.
             * 남은 넷 가운데 7장 밖으로 밀리는 것은 점수가 가장 낮은 하나다.
             * 21번은 가장 가깝지만 할인과 선호가 없고, 22번은 조금 멀어도 찜을 다 받은 업종이다.
             */
            List<NearbyCandidate> nearby = new ArrayList<>();
            Map<Long, BudgetProgress> progress = new HashMap<>();
            for (long id = 1; id <= 3; id++) {
                nearby.add(near(id, 900));
                progress.put(id, progress(id, 0.1));
            }
            for (long id = 11; id <= 16; id++) {
                nearby.add(new NearbyCandidate(candidate(id, 300).discount("PERCENT", 50).build(), 300));
                progress.put(id, progress(id, 0.9));
            }
            nearby.add(new NearbyCandidate(candidate(21L, 100).discount("PERCENT", 1).build(), 100));
            nearby.add(new NearbyCandidate(candidate(22L, 400).category("JAPANESE").discount("PERCENT", 1).build(), 400));
            progress.put(21L, progress(21L, 0.9));
            progress.put(22L, progress(22L, 0.9));

            FeedAssembly result = FeedAssembler.assemble(nearby, progress, Map.of(), Map.of("JAPANESE", 3L),
                    FeedSort.DISTANCE);

            assertThat(campaignIds(result)).contains(22L).doesNotContain(21L);
        }

        @Test
        void 금액_할인은_1만원을_1로_보고_1에서_자른다() {
            assertThat(FeedAssembler.discountScore(candidate(1L, 100).discount("AMOUNT", 20_000).build()))
                    .isEqualTo(1.0);
            assertThat(FeedAssembler.discountScore(candidate(2L, 100).discount("AMOUNT", 5_000).build()))
                    .isEqualTo(0.5);
            assertThat(FeedAssembler.discountScore(candidate(3L, 100).discount("PERCENT", 30).build()))
                    .isEqualTo(0.3);
        }

        @Test
        void 거리순이면_고른_관련성_카드를_가까운_순으로_놓는다() {
            FeedAssembly result = relevanceOnly(FeedSort.DISTANCE);

            assertThat(relevanceCampaignIds(result)).containsExactly(12L, 11L, 13L);
        }

        @Test
        void 할인순이면_고른_관련성_카드를_할인이_큰_순으로_놓는다() {
            FeedAssembly result = relevanceOnly(FeedSort.DISCOUNT_RATE);

            assertThat(relevanceCampaignIds(result)).containsExactly(13L, 11L, 12L);
        }

        private FeedAssembly relevanceOnly(FeedSort sort) {
            List<NearbyCandidate> nearby = new ArrayList<>();
            Map<Long, BudgetProgress> progress = new HashMap<>();
            for (long id = 1; id <= 3; id++) {
                nearby.add(near(id, 900));
                progress.put(id, progress(id, 0.1));
            }
            nearby.add(new NearbyCandidate(candidate(11L, 200).discount("PERCENT", 20).build(), 200));
            nearby.add(new NearbyCandidate(candidate(12L, 100).discount("PERCENT", 10).build(), 100));
            nearby.add(new NearbyCandidate(candidate(13L, 300).discount("PERCENT", 30).build(), 300));
            for (long id = 11; id <= 13; id++) {
                progress.put(id, progress(id, 0.9));
            }
            return FeedAssembler.assemble(nearby, progress, Map.of(), Map.of(), sort);
        }

        private List<Long> relevanceCampaignIds(FeedAssembly assembly) {
            return assembly.cards().stream()
                    .filter(card -> card.slotType() == SlotType.RELEVANCE)
                    .map(card -> card.nearby().campaignId())
                    .toList();
        }
    }

    @Nested
    class 탐색_반경 {

        @Test
        void 반경_1000_안에_10장이_있으면_넓히지_않는다() {
            List<NearbyCandidate> nearby = spread(10, 900);
            nearby.add(near(99L, 1500));

            FeedAssembly result = FeedAssembler.assemble(nearby, progressOf(nearby), Map.of(), Map.of(),
                    FeedSort.DISTANCE);

            assertThat(result.radiusM()).isEqualTo(1000);
            assertThat(campaignIds(result)).doesNotContain(99L);
        }

        @Test
        void 반경_안의_후보가_10장_미만이면_다음_반경으로_넓힌다() {
            List<NearbyCandidate> nearby = spread(3, 900);
            nearby.addAll(spreadFrom(100, 7, 1900));

            FeedAssembly result = FeedAssembler.assemble(nearby, progressOf(nearby), Map.of(), Map.of(),
                    FeedSort.DISTANCE);

            assertThat(result.radiusM()).isEqualTo(2000);
            assertThat(result.cards()).hasSize(10);
        }

        @Test
        void 반경_5000까지_넓혀도_모자라면_있는_만큼_내려준다() {
            List<NearbyCandidate> nearby = spread(2, 4900);

            FeedAssembly result = FeedAssembler.assemble(nearby, progressOf(nearby), Map.of(), Map.of(),
                    FeedSort.DISTANCE);

            assertThat(result.radiusM()).isEqualTo(5000);
            assertThat(result.cards()).hasSize(2);
        }

        @Test
        void 후보가_없으면_반경_5000과_빈_카드다() {
            FeedAssembly result = FeedAssembler.assemble(List.of(), Map.of(), Map.of(), Map.of(), FeedSort.DISTANCE);

            assertThat(result.radiusM()).isEqualTo(5000);
            assertThat(result.cards()).isEmpty();
        }

        private List<NearbyCandidate> spread(int count, int distanceM) {
            return spreadFrom(1, count, distanceM);
        }

        private List<NearbyCandidate> spreadFrom(long firstId, int count, int distanceM) {
            return new ArrayList<>(LongStream.range(firstId, firstId + count)
                    .mapToObj(id -> near(id, distanceM))
                    .toList());
        }

        private Map<Long, BudgetProgress> progressOf(List<NearbyCandidate> nearby) {
            Map<Long, BudgetProgress> progress = new HashMap<>();
            nearby.forEach(n -> progress.put(n.campaignId(), progress(n.campaignId(), 0.5)));
            return progress;
        }
    }

    private static List<Long> campaignIds(FeedAssembly assembly) {
        return assembly.cards().stream().map(card -> card.nearby().campaignId()).toList();
    }

    private static NearbyCandidate near(long campaignId, int distanceM) {
        return new NearbyCandidate(candidate(campaignId, distanceM).build(), distanceM);
    }

    private static BudgetProgress progress(long campaignId, double rate) {
        return new BudgetProgress(campaignId, 100_000, 10, 0, 0, rate);
    }

    private static CandidateBuilder candidate(long campaignId, int distanceNorthM) {
        return new CandidateBuilder(campaignId, distanceNorthM);
    }

    private static final class CandidateBuilder {

        private final long campaignId;
        private final int distanceNorthM;
        private int targetRadius = 3000;
        private String targetGender = "ALL";
        private Set<String> targetAgeGroups = Set.of("AGE_20S", "AGE_30S", "AGE_40S", "AGE_50_PLUS");
        private String category = "KOREAN";
        private String discountType = "PERCENT";
        private int discountValue = 10;
        private LocalDateTime createdAt = CREATED;

        private CandidateBuilder(long campaignId, int distanceNorthM) {
            this.campaignId = campaignId;
            this.distanceNorthM = distanceNorthM;
        }

        CandidateBuilder targetRadius(int value) {
            this.targetRadius = value;
            return this;
        }

        CandidateBuilder targetGender(String value) {
            this.targetGender = value;
            return this;
        }

        CandidateBuilder targetAgeGroups(Set<String> value) {
            this.targetAgeGroups = value;
            return this;
        }

        CandidateBuilder category(String value) {
            this.category = value;
            return this;
        }

        CandidateBuilder discount(String type, int value) {
            this.discountType = type;
            this.discountValue = value;
            return this;
        }

        CandidateBuilder createdAt(LocalDateTime value) {
            this.createdAt = value;
            return this;
        }

        ServableCandidate build() {
            return new ServableCandidate(campaignId, campaignId + 1000, "가게" + campaignId, category,
                    USER_LAT + distanceNorthM / METERS_PER_LAT_DEGREE, USER_LNG, targetRadius, targetGender,
                    targetAgeGroups, "ALL", discountType, discountValue, LocalTime.of(12, 0), LocalTime.of(14, 0),
                    30, createdAt);
        }
    }
}
