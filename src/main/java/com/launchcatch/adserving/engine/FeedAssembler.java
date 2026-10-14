package com.launchcatch.adserving.engine;

import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 피드 카드 10장을 고르는 계산이다. HTTP, DB, 캐시에 기대지 않는다.
 * 배분 3장(1, 4, 7번)은 예산 소진 진행률이 낮은 순이고 관련성 7장은 관련성 점수 순이다(96, 97, 99행).
 */
public final class FeedAssembler {

    public static final int MAX_SEARCH_RADIUS_M = 5000;

    private static final int[] SEARCH_RADII_M = {1000, 2000, 3000, MAX_SEARCH_RADIUS_M};
    private static final int FEED_SIZE = 10;
    private static final int ALLOCATION_SIZE = 3;
    private static final Set<Integer> ALLOCATION_POSITIONS = Set.of(1, 4, 7);
    private static final String ALL_GENDERS = "ALL";

    // 진행률이 이 값을 넘거나 그날 유효 노출이 상한에 닿은 캠페인은 두 슬롯 모두에서 뺀다(99행)
    private static final double MAX_PROGRESS_RATE = 1.2;
    private static final long IMPRESSION_CAP_PERCENT = 105;

    private static final double DISTANCE_WEIGHT = 0.45;
    private static final double CATEGORY_WEIGHT = 0.35;
    private static final double DISCOUNT_WEIGHT = 0.20;
    // 금액 할인은 이 금액을 할인율 100% 와 같은 점수로 본다(#236 결정)
    private static final double FULL_SCORE_DISCOUNT_AMOUNT = 10_000;

    private FeedAssembler() {
    }

    // 캠페인의 노출 반경, 대상 성별과 연령대, 업종 필터, 제외할 캠페인으로 거른다
    public static List<NearbyCandidate> eligible(List<ServableCandidate> candidates, double latitude, double longitude,
            Gender gender, AgeGroup ageGroup, FeedFilter filter, Set<Long> excludedCampaignIds) {
        List<NearbyCandidate> result = new ArrayList<>();
        for (ServableCandidate candidate : candidates) {
            if (excludedCampaignIds.contains(candidate.campaignId())
                    || !filter.allows(candidate.categoryCode())
                    || !targets(candidate, gender, ageGroup)) {
                continue;
            }
            int distanceM = (int) Math.round(
                    GeoDistance.meters(latitude, longitude, candidate.latitude(), candidate.longitude()));
            if (distanceM <= candidate.targetRadiusM()) {
                result.add(new NearbyCandidate(candidate, distanceM));
            }
        }
        return result;
    }

    /*
     * 예산으로 거른 뒤 탐색 반경을 정하고 슬롯을 채운다.
     * 반경 안의 후보가 10장 미만이면 다음 반경으로 넓히고, 5000 에서도 모자라면 있는 만큼 내려준다.
     */
    public static FeedAssembly assemble(List<NearbyCandidate> eligible, Map<Long, BudgetProgress> progress,
            Map<Long, Long> validImpressions, Map<String, Long> wishesByCategory, FeedSort sort) {
        List<NearbyCandidate> withinBudget = eligible.stream()
                .filter(nearby -> withinBudget(progress.get(nearby.campaignId()),
                        validImpressions.getOrDefault(nearby.campaignId(), 0L)))
                .toList();

        int radiusM = MAX_SEARCH_RADIUS_M;
        List<NearbyCandidate> inRadius = withinBudget;
        for (int candidateRadius : SEARCH_RADII_M) {
            List<NearbyCandidate> found = withinBudget.stream()
                    .filter(nearby -> nearby.distanceM() <= candidateRadius)
                    .toList();
            if (found.size() >= FEED_SIZE || candidateRadius == MAX_SEARCH_RADIUS_M) {
                radiusM = candidateRadius;
                inRadius = found;
                break;
            }
        }

        List<NearbyCandidate> allocation = inRadius.stream()
                .sorted(Comparator.<NearbyCandidate>comparingDouble(n -> progress.get(n.campaignId()).progressRate())
                        .thenComparing(n -> n.candidate().campaignCreatedAt())
                        .thenComparing(NearbyCandidate::campaignId))
                .limit(ALLOCATION_SIZE)
                .toList();
        List<NearbyCandidate> relevance = relevance(inRadius, allocation, wishesByCategory, radiusM, sort);
        return new FeedAssembly(radiusM, place(allocation, relevance));
    }

    static double discountScore(ServableCandidate candidate) {
        if ("PERCENT".equals(candidate.discountType())) {
            return candidate.discountValue() / 100.0;
        }
        return Math.min(candidate.discountValue() / FULL_SCORE_DISCOUNT_AMOUNT, 1.0);
    }

    private static boolean targets(ServableCandidate candidate, Gender gender, AgeGroup ageGroup) {
        boolean genderMatches = ALL_GENDERS.equals(candidate.targetGender())
                || candidate.targetGender().equals(gender.name());
        return genderMatches && candidate.targetAgeGroups().contains(ageGroup.name());
    }

    // 예산 계획이 없으면 그날 예약이 없어 후보가 아니다
    private static boolean withinBudget(BudgetProgress progress, long validImpressions) {
        if (progress == null || progress.progressRate() > MAX_PROGRESS_RATE) {
            return false;
        }
        long cap = progress.reservedAmount() * IMPRESSION_CAP_PERCENT / (progress.unitPrice() * 100);
        return validImpressions < cap;
    }

    // 관련성 점수로 7장을 고른 뒤 그 7장의 순서만 정렬로 바꾼다(#236 결정)
    private static List<NearbyCandidate> relevance(List<NearbyCandidate> inRadius, List<NearbyCandidate> allocation,
            Map<String, Long> wishesByCategory, int radiusM, FeedSort sort) {
        long totalWishes = wishesByCategory.values().stream().mapToLong(Long::longValue).sum();
        Comparator<NearbyCandidate> byScore = Comparator
                .comparingDouble((NearbyCandidate n) -> relevanceScore(n, wishesByCategory, totalWishes, radiusM))
                .reversed()
                .thenComparingInt(NearbyCandidate::distanceM)
                .thenComparing(NearbyCandidate::campaignId);
        Comparator<NearbyCandidate> bySort = switch (sort) {
            case DISTANCE -> Comparator.comparingInt(NearbyCandidate::distanceM);
            case DISCOUNT_RATE -> Comparator.comparingDouble((NearbyCandidate n) -> discountScore(n.candidate()))
                    .reversed()
                    .thenComparingInt(NearbyCandidate::distanceM);
        };
        return inRadius.stream()
                .filter(nearby -> !allocation.contains(nearby))
                .sorted(byScore)
                .limit(FEED_SIZE - ALLOCATION_SIZE)
                .sorted(bySort.thenComparing(NearbyCandidate::campaignId))
                .toList();
    }

    private static double relevanceScore(NearbyCandidate nearby, Map<String, Long> wishesByCategory, long totalWishes,
            int radiusM) {
        double distance = Math.max(0.0, 1.0 - (double) nearby.distanceM() / radiusM);
        double category = totalWishes == 0
                ? 0.0
                : (double) wishesByCategory.getOrDefault(nearby.candidate().categoryCode(), 0L) / totalWishes;
        return DISTANCE_WEIGHT * distance + CATEGORY_WEIGHT * category
                + DISCOUNT_WEIGHT * discountScore(nearby.candidate());
    }

    // 배분 카드를 1, 4, 7번에 두고 나머지를 관련성 카드로 채운다. 한쪽이 모자라면 다른 쪽으로 채워 앞으로 당긴다
    private static List<SlotCard> place(List<NearbyCandidate> allocation, List<NearbyCandidate> relevance) {
        Deque<NearbyCandidate> allocationLeft = new ArrayDeque<>(allocation);
        Deque<NearbyCandidate> relevanceLeft = new ArrayDeque<>(relevance);
        List<SlotCard> cards = new ArrayList<>();
        int position = 1;
        while (!allocationLeft.isEmpty() || !relevanceLeft.isEmpty()) {
            boolean allocationTurn = ALLOCATION_POSITIONS.contains(position) || relevanceLeft.isEmpty();
            if (allocationTurn && !allocationLeft.isEmpty()) {
                cards.add(new SlotCard(allocationLeft.poll(), SlotType.ALLOCATION, position));
            } else {
                cards.add(new SlotCard(relevanceLeft.poll(), SlotType.RELEVANCE, position));
            }
            position++;
        }
        return cards;
    }
}
