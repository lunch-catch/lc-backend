package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("피드 배정")
class FeedAssemblerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 1, 9, 0);

    @Test
    void 배분_3장을_1_4_7번에_두고_나머지_7장을_관련성으로_채운다() {
        List<Eligible> pool = new ArrayList<>();
        pool.add(eligible(candidate(1L), 900, 0.1));
        pool.add(eligible(candidate(2L), 900, 0.2));
        pool.add(eligible(candidate(3L), 900, 0.3));
        IntStream.range(0, 8).forEach(i -> pool.add(eligible(candidate(10L + i), 100 + i * 10, 0.9)));

        Assembly assembly = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.cards()).hasSize(10);
        assertThat(assembly.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                .extracting(AssignedCard::slotPosition).containsExactly(1, 4, 7);
        assertThat(assembly.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                .extracting(card -> card.candidate().campaignId()).containsExactly(1L, 2L, 3L);
        assertThat(assembly.cards()).extracting(card -> card.candidate().campaignId()).doesNotContain(17L);
    }

    @Test
    void 배분_슬롯은_신규_우선_진행률_생성_시각_순이다() {
        List<Eligible> pool = List.of(
                eligible(candidate(1L), 100, 0.1),
                eligible(candidate(2L).withNewPriorityUntil(TODAY), 100, 0.9),
                eligible(candidate(3L).withCreatedAt(CREATED.minusDays(1)), 100, 0.5),
                eligible(candidate(4L), 100, 0.5),
                eligible(candidate(5L).withNewPriorityUntil(TODAY.minusDays(1)), 100, 0.05));

        Assembly assembly = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.cards()).filteredOn(card -> card.slotType() == SlotType.ALLOCATION)
                .extracting(card -> card.candidate().campaignId()).containsExactly(2L, 5L, 1L);
    }

    @Test
    void 반경을_넓혀_가다가_후보가_10장_이상인_첫_반경에서_멈춘다() {
        List<Eligible> pool = new ArrayList<>();
        IntStream.range(0, 6).forEach(i -> pool.add(eligible(candidate(1L + i), 900, 0.5)));
        IntStream.range(0, 6).forEach(i -> pool.add(eligible(candidate(11L + i), 1500, 0.5)));
        pool.add(eligible(candidate(21L), 2500, 0.5));

        Assembly assembly = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.radiusM()).isEqualTo(2000);
        assertThat(assembly.cards()).extracting(card -> card.candidate().campaignId()).doesNotContain(21L);
    }

    @Test
    void 후보가_모자라면_가장_넓은_반경에서_빈_자리_없이_앞으로_당긴다() {
        List<Eligible> pool = List.of(
                eligible(candidate(1L), 100, 0.1),
                eligible(candidate(2L), 200, 0.2),
                eligible(candidate(3L), 300, 0.3),
                eligible(candidate(4L), 400, 0.4),
                eligible(candidate(5L), 500, 0.5));

        Assembly assembly = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.radiusM()).isEqualTo(5000);
        assertThat(assembly.cards()).extracting(AssignedCard::slotType).containsExactly(
                SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE, SlotType.ALLOCATION, SlotType.ALLOCATION);
        assertThat(assembly.cards()).extracting(AssignedCard::slotPosition).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void 후보가_없으면_가장_넓은_반경과_빈_카드다() {
        Assembly assembly = FeedAssembler.assemble(List.of(), TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.radiusM()).isEqualTo(5000);
        assertThat(assembly.cards()).isEmpty();
    }

    @Test
    void 캠페인이_고른_노출_반경_밖이면_탐색_반경_안이어도_뺀다() {
        List<Eligible> pool = List.of(eligible(candidate(1L).withTargetRadius(500), 600, 0.1));

        Assembly assembly = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);

        assertThat(assembly.cards()).isEmpty();
    }

    @Test
    void 할인율순_정렬은_관련성_7장의_순서만_바꾼다() {
        List<Eligible> pool = new ArrayList<>();
        pool.add(eligible(candidate(1L), 900, 0.1));
        pool.add(eligible(candidate(2L), 900, 0.2));
        pool.add(eligible(candidate(3L), 900, 0.3));
        pool.add(eligible(candidate(10L).withDiscount(DiscountType.PERCENT, 10), 100, 0.9));
        pool.add(eligible(candidate(11L).withDiscount(DiscountType.PERCENT, 50), 200, 0.9));
        pool.add(eligible(candidate(12L).withDiscount(DiscountType.AMOUNT, 3000), 300, 0.9));

        Assembly distance = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISTANCE);
        Assembly discount = FeedAssembler.assemble(pool, TODAY, Map.of(), FeedSort.DISCOUNT_RATE);

        assertThat(relevanceIds(distance)).containsExactly(10L, 11L, 12L);
        assertThat(relevanceIds(discount)).containsExactly(11L, 12L, 10L);
        assertThat(allocationIds(discount)).isEqualTo(allocationIds(distance));
    }

    @Test
    void 임시_관련성_점수는_거리_45_카테고리_35_할인_20_이다() {
        Eligible eligible = eligible(candidate(1L).withCategory("JAPANESE").withDiscount(DiscountType.PERCENT, 50), 500, 0);

        double score = FeedAssembler.relevanceScore(eligible, 1000, Map.of("JAPANESE", 2L, "KOREAN", 4L), 4);

        assertThat(score).isCloseTo(0.45 * 0.5 + 0.35 * 0.5 + 0.20 * 0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void 임시_금액_할인은_만원을_넘으면_할인율_100_과_같은_점수다() {
        assertThat(FeedAssembler.discountScore(candidate(1L).withDiscount(DiscountType.AMOUNT, 3000).build()))
                .isEqualTo(0.3);
        assertThat(FeedAssembler.discountScore(candidate(1L).withDiscount(DiscountType.AMOUNT, 15000).build()))
                .isEqualTo(1.0);
    }

    private static List<Long> relevanceIds(Assembly assembly) {
        return assembly.cards().stream()
                .filter(card -> card.slotType() == SlotType.RELEVANCE)
                .map(card -> card.candidate().campaignId())
                .toList();
    }

    private static List<Long> allocationIds(Assembly assembly) {
        return assembly.cards().stream()
                .filter(card -> card.slotType() == SlotType.ALLOCATION)
                .map(card -> card.candidate().campaignId())
                .toList();
    }

    private static Eligible eligible(TestCandidate candidate, int distanceM, double progressRate) {
        return new Eligible(candidate.build(), distanceM, progressRate);
    }

    private static TestCandidate candidate(Long campaignId) {
        return new TestCandidate(campaignId, 3000, "KOREAN", DiscountType.PERCENT, 20, CREATED, null);
    }

    private record TestCandidate(Long campaignId, int targetRadius, String category, DiscountType discountType,
                                 int discountValue, LocalDateTime createdAt, LocalDate newPriorityUntil) {

        TestCandidate withTargetRadius(int value) {
            return new TestCandidate(campaignId, value, category, discountType, discountValue, createdAt, newPriorityUntil);
        }

        TestCandidate withCategory(String value) {
            return new TestCandidate(campaignId, targetRadius, value, discountType, discountValue, createdAt, newPriorityUntil);
        }

        TestCandidate withDiscount(DiscountType type, int value) {
            return new TestCandidate(campaignId, targetRadius, category, type, value, createdAt, newPriorityUntil);
        }

        TestCandidate withCreatedAt(LocalDateTime value) {
            return new TestCandidate(campaignId, targetRadius, category, discountType, discountValue, value, newPriorityUntil);
        }

        TestCandidate withNewPriorityUntil(LocalDate value) {
            return new TestCandidate(campaignId, targetRadius, category, discountType, discountValue, createdAt, value);
        }

        Candidate build() {
            return new Candidate(campaignId, campaignId + 1000, "가게" + campaignId, category, 37.5, 127.0,
                    targetRadius, TargetGender.ALL, EnumSet.allOf(AgeGroup.class), DiscountTargetType.ALL,
                    discountType, discountValue, LocalTime.of(12, 0), LocalTime.of(14, 0), 30, createdAt,
                    newPriorityUntil);
        }
    }
}
