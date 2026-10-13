package com.launchcatch.adserving.engine;

import com.launchcatch.campaign.contract.DiscountType;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * 조건을 통과한 후보로 피드 10장을 배정한다(96, 97, 99행).
 *
 * 탐색 반경을 1000, 2000, 3000, 5000 으로 넓혀 가다가 후보가 10장 이상이 되는 첫 반경에서 멈춘다.
 * 배분 3장(1, 4, 7번)은 신규 우선, 예산 소진 진행률 오름차순, 캠페인 생성 시각 순으로 고른다.
 * 관련성 7장은 남은 후보에서 관련성 점수(거리 45, 카테고리 선호도 35, 할인 20) 순으로 고른다.
 * 피드 필터의 정렬은 고른 관련성 7장의 순서에만 건다(70행).
 */
public final class FeedAssembler {

    static final List<Integer> SEARCH_RADII = List.of(1000, 2000, 3000, 5000);

    /*
     * 후보를 찾는 가장 넓은 탐색 반경이다.
     * 부르는 쪽이 이 반경의 상자로 후보를 읽어 온다.
     */
    public static final int MAX_SEARCH_RADIUS = SEARCH_RADII.getLast();
    static final int FEED_SIZE = 10;
    static final int ALLOCATION_SLOTS = 3;

    private static final Set<Integer> ALLOCATION_POSITIONS = Set.of(1, 4, 7);
    private static final double DISTANCE_WEIGHT = 0.45;
    private static final double CATEGORY_WEIGHT = 0.35;
    private static final double DISCOUNT_WEIGHT = 0.20;

    /*
     * 임시(#230): 금액 할인을 점수로 옮기는 상한이다.
     * 이 금액 이상이면 할인율 100% 와 같은 점수를 준다.
     */
    private static final double AMOUNT_DISCOUNT_CEILING = 10_000;
    private static final double PERCENT_DENOMINATOR = 100;

    private FeedAssembler() {
    }

    /*
     * wishCountsByCategory 는 회원이 최근 30일에 업종마다 찜한 횟수다.
     * 후보가 하나도 없으면 가장 넓은 반경과 빈 카드 목록을 돌려준다.
     */
    public static Assembly assemble(
            List<Eligible> eligible, LocalDate businessDate, Map<String, Long> wishCountsByCategory, FeedSort sort) {
        int radius = SEARCH_RADII.getFirst();
        List<Eligible> pool = List.of();
        for (int searchRadius : SEARCH_RADII) {
            radius = searchRadius;
            pool = within(eligible, searchRadius);
            if (pool.size() >= FEED_SIZE) {
                break;
            }
        }
        return new Assembly(radius, place(pool, radius, businessDate, wishCountsByCategory, sort));
    }

    // 탐색 반경과 캠페인이 고른 노출 반경 둘 다의 안쪽에 있는 후보만 남긴다
    private static List<Eligible> within(List<Eligible> eligible, int searchRadius) {
        return eligible.stream()
                .filter(e -> e.distanceM() <= searchRadius && e.distanceM() <= e.candidate().targetRadius())
                .toList();
    }

    private static List<AssignedCard> place(
            List<Eligible> pool, int radius, LocalDate businessDate, Map<String, Long> wishCounts, FeedSort sort) {
        List<Eligible> allocation = pool.stream()
                .sorted(allocationOrder(businessDate))
                .limit(ALLOCATION_SLOTS)
                .toList();
        Set<Long> allocated = allocation.stream().map(e -> e.candidate().campaignId()).collect(Collectors.toSet());

        long maxWishCount = wishCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        Comparator<Eligible> byRelevance = Comparator
                .comparingDouble((Eligible e) -> relevanceScore(e, radius, wishCounts, maxWishCount))
                .reversed()
                .thenComparingInt(Eligible::distanceM)
                .thenComparing(e -> e.candidate().campaignId());
        List<Eligible> relevance = pool.stream()
                .filter(e -> !allocated.contains(e.candidate().campaignId()))
                .sorted(byRelevance)
                .limit(FEED_SIZE - ALLOCATION_SLOTS)
                .sorted(sortOrder(sort))
                .toList();

        return interleave(new ArrayDeque<>(allocation), new ArrayDeque<>(relevance));
    }

    /*
     * 배분 후보는 1, 4, 7번에, 관련성 후보는 나머지에 둔다.
     * 한쪽이 모자라면 다른 쪽이 그 자리를 채워 빈 자리 없이 앞으로 당긴다.
     */
    private static List<AssignedCard> interleave(Deque<Eligible> allocation, Deque<Eligible> relevance) {
        List<AssignedCard> cards = new ArrayList<>();
        for (int position = 1; position <= FEED_SIZE; position++) {
            boolean allocationSlot = ALLOCATION_POSITIONS.contains(position);
            Deque<Eligible> preferred = allocationSlot ? allocation : relevance;
            Deque<Eligible> source = preferred.isEmpty() ? (allocationSlot ? relevance : allocation) : preferred;
            if (source.isEmpty()) {
                break;
            }
            SlotType slotType = source == allocation ? SlotType.ALLOCATION : SlotType.RELEVANCE;
            Eligible next = source.poll();
            cards.add(new AssignedCard(next.candidate(), next.distanceM(), slotType, cards.size() + 1));
        }
        return cards;
    }

    private static Comparator<Eligible> allocationOrder(LocalDate businessDate) {
        return Comparator.comparing((Eligible e) -> !e.candidate().newPriorityOn(businessDate))
                .thenComparingDouble(Eligible::progressRate)
                .thenComparing(e -> e.candidate().campaignCreatedAt())
                .thenComparing(e -> e.candidate().campaignId());
    }

    private static Comparator<Eligible> sortOrder(FeedSort sort) {
        Comparator<Eligible> byDistance = Comparator.comparingInt(Eligible::distanceM);
        if (sort == FeedSort.DISCOUNT_RATE) {
            return Comparator.comparingDouble((Eligible e) -> discountScore(e.candidate()))
                    .reversed()
                    .thenComparing(byDistance)
                    .thenComparing(e -> e.candidate().campaignId());
        }
        return byDistance.thenComparing(e -> e.candidate().campaignId());
    }

    static double relevanceScore(Eligible eligible, int radius, Map<String, Long> wishCounts, long maxWishCount) {
        double distanceScore = 1 - (double) eligible.distanceM() / radius;
        long wishCount = wishCounts.getOrDefault(eligible.candidate().categoryCode(), 0L);
        double categoryScore = maxWishCount == 0 ? 0 : (double) wishCount / maxWishCount;
        return DISTANCE_WEIGHT * distanceScore
                + CATEGORY_WEIGHT * categoryScore
                + DISCOUNT_WEIGHT * discountScore(eligible.candidate());
    }

    // 임시(#230): 할인율은 값/100, 금액 할인은 상한으로 나눈 값을 0~1 로 쓴다
    static double discountScore(Candidate candidate) {
        if (candidate.discountType() == DiscountType.PERCENT) {
            return candidate.discountValue() / PERCENT_DENOMINATOR;
        }
        return Math.min(candidate.discountValue() / AMOUNT_DISCOUNT_CEILING, 1);
    }
}
