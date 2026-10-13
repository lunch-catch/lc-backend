package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.campaign.contract.DiscountType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/*
 * 거른 후보를 배분 슬롯 3장과 관련성 슬롯 7장에 배정한다(96, 97행).
 * 배분 슬롯은 1, 4, 7번 자리이고 정렬과 상관없이 정해진다. 관련성 슬롯은 점수로 7장을 고른 뒤 피드 필터의 정렬로 놓는다(70행).
 * 한쪽 후보가 모자라면 빈 자리를 남기지 않고 다음 카드를 당겨 채운다.
 */
final class SlotAllocator {

    private static final int ALLOCATION_SLOTS = 3;
    private static final int RELEVANCE_SLOTS = 7;
    private static final Set<Integer> ALLOCATION_POSITIONS = Set.of(1, 4, 7);

    /*
     * 관련성 점수의 가중치다(97행).
     * 거리 45, 카테고리 선호도 35, 할인 20
     */
    private static final double DISTANCE_WEIGHT = 0.45;
    private static final double CATEGORY_WEIGHT = 0.35;
    private static final double DISCOUNT_WEIGHT = 0.20;

    /*
     * 할인 금액을 할인율과 같은 0 ~ 1 척도로 옮길 때 1 로 보는 금액이다.
     * 명세가 정규화를 정하지 않아 임시로 정했다(#187).
     */
    private static final int FULL_SCORE_DISCOUNT_AMOUNT = 10_000;

    List<PlannedCard> allocate(List<Placed> pool, int radius, FeedInput input) {
        List<Placed> allocation = pool.stream()
                .sorted(allocationOrder(input))
                .limit(ALLOCATION_SLOTS)
                .toList();

        List<Placed> relevance = pool.stream()
                .filter(placed -> !allocation.contains(placed))
                .sorted(Comparator.comparingDouble((Placed placed) -> -relevanceScore(placed, radius, input.preference()))
                        .thenComparingLong(placed -> placed.candidate().campaignId()))
                .limit(RELEVANCE_SLOTS)
                .sorted(sortOrder(input.sort()))
                .toList();

        return interleave(allocation, relevance);
    }

    // 신규 우선 노출 먼저, 그다음 예산 소진 진행률이 낮은 순, 그다음 먼저 만든 캠페인이다
    private static Comparator<Placed> allocationOrder(FeedInput input) {
        return Comparator.comparing((Placed placed) -> !placed.candidate().newPriorityOn(input.businessDate()))
                .thenComparingDouble(placed -> input.budget().progressRate(placed.candidate().campaignId()))
                .thenComparing(placed -> placed.candidate().campaignCreatedAt())
                .thenComparingLong(placed -> placed.candidate().campaignId());
    }

    private static Comparator<Placed> sortOrder(FeedSort sort) {
        Comparator<Placed> byDistance = Comparator.comparingDouble(Placed::distanceM);
        Comparator<Placed> primary = sort == FeedSort.DISCOUNT_RATE
                ? Comparator.comparingDouble((Placed placed) -> -discountScore(placed.candidate())).thenComparing(byDistance)
                : byDistance;
        return primary.thenComparingLong(placed -> placed.candidate().campaignId());
    }

    static double relevanceScore(Placed placed, int radius, CategoryPreference preference) {
        double distance = Math.max(0, 1 - placed.distanceM() / radius);
        return DISTANCE_WEIGHT * distance
                + CATEGORY_WEIGHT * preference.share(placed.candidate().categoryCode())
                + DISCOUNT_WEIGHT * discountScore(placed.candidate());
    }

    static double discountScore(FeedCandidate candidate) {
        if (candidate.discountType() == DiscountType.PERCENT) {
            return candidate.discountValue() / 100.0;
        }
        return Math.min(candidate.discountValue(), FULL_SCORE_DISCOUNT_AMOUNT) / (double) FULL_SCORE_DISCOUNT_AMOUNT;
    }

    private static List<PlannedCard> interleave(List<Placed> allocation, List<Placed> relevance) {
        Iterator<Placed> allocated = allocation.iterator();
        Iterator<Placed> relevant = relevance.iterator();
        List<PlannedCard> cards = new ArrayList<>();
        while (allocated.hasNext() || relevant.hasNext()) {
            int position = cards.size() + 1;
            boolean allocationTurn = ALLOCATION_POSITIONS.contains(position) ? allocated.hasNext() : !relevant.hasNext();
            Placed next = allocationTurn ? allocated.next() : relevant.next();
            SlotType slotType = allocationTurn ? SlotType.ALLOCATION : SlotType.RELEVANCE;
            cards.add(new PlannedCard(next.candidate(), (int) Math.round(next.distanceM()), slotType, position));
        }
        return cards;
    }
}
