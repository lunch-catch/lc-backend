package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.entity.DiscountType;
import java.util.ArrayDeque;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/*
 * 걸러 낸 후보로 카드 10장의 자리를 정한다. 저장소를 읽지 않는 순수 계산이다.
 *
 * 배분 3장은 1, 4, 7번에 두고 예산 소진 진행률이 낮은 순으로 고른다. 나머지 7장은 관련성 점수로 고르고
 * 사용자가 고른 정렬로 늘어놓는다. 한쪽이 모자라면 다른 쪽이 그 자리를 채운다.
 *
 * 관련성 점수의 각 항을 0 ~ 1 로 맞추는 식은 설계서에 없어 #236 에서 임시로 정했다.
 * 거리는 1 - 거리 / 탐색 반경, 할인은 할인율 또는 1만 원을 1 로 본 할인 금액이다.
 */
public final class FeedAssembler {

    public static final int FEED_SIZE = 10;

    // 후보가 모자라면 이 순서로 탐색 반경을 넓힌다
    public static final List<Integer> SEARCH_RADII = List.of(1000, 2000, 3000, 5000);

    static final double DISTANCE_WEIGHT = 0.45;
    static final double CATEGORY_WEIGHT = 0.35;
    static final double DISCOUNT_WEIGHT = 0.20;

    private static final int ALLOCATION_SIZE = 3;
    private static final double AMOUNT_FOR_FULL_SCORE = 10_000.0;
    private static final List<SlotType> LAYOUT = List.of(
            SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE,
            SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE,
            SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE, SlotType.RELEVANCE);

    private static final Comparator<Option> BY_ALLOCATION = Comparator
            .comparingDouble(Option::progressRate)
            .thenComparing(Option::campaignCreatedAt)
            .thenComparing(Option::campaignId);

    private FeedAssembler() {
    }

    /*
     * 후보 하나다. distanceM 은 요청 위치에서 가게까지의 거리, progressRate 는 그날 예산 소진 진행률,
     * categoryPreference 는 그 업종에 대한 사용자 선호도(0 ~ 1)다.
     */
    public record Option(Long campaignId, LocalDateTime campaignCreatedAt, DiscountType discountType,
                         int discountValue, double distanceM, double progressRate, double categoryPreference) {
    }

    public record Slot(Option option, SlotType slotType, int position) {
    }

    public record Feed(int radiusM, List<Slot> slots) {
    }

    /*
     * 후보가 10장 이상 모이는 가장 작은 반경을 고르고 그 반경 안의 후보로 자리를 정한다.
     * 가장 큰 반경에서도 모자라면 있는 만큼만 채운다. 후보가 없으면 빈 slots 다.
     */
    public static Feed assemble(List<Option> options, FeedSort sort) {
        int radius = chooseRadius(options);
        List<Option> inRadius = options.stream()
                .filter(option -> option.distanceM() <= radius)
                .toList();

        List<Option> allocation = inRadius.stream()
                .sorted(BY_ALLOCATION)
                .limit(ALLOCATION_SIZE)
                .toList();
        List<Option> relevance = inRadius.stream()
                .filter(option -> !allocation.contains(option))
                .sorted(Comparator.comparingDouble((Option option) -> relevanceScore(option, radius)).reversed()
                        .thenComparing(Option::campaignId))
                .limit(FEED_SIZE - allocation.size())
                .sorted(displayOrder(sort))
                .toList();

        return new Feed(radius, layout(allocation, relevance));
    }

    static double relevanceScore(Option option, int radius) {
        double distanceScore = Math.max(0.0, 1.0 - option.distanceM() / radius);
        return DISTANCE_WEIGHT * distanceScore
                + CATEGORY_WEIGHT * option.categoryPreference()
                + DISCOUNT_WEIGHT * discountScore(option);
    }

    static double discountScore(Option option) {
        if (option.discountType() == DiscountType.PERCENT) {
            return option.discountValue() / 100.0;
        }
        return Math.min(option.discountValue() / AMOUNT_FOR_FULL_SCORE, 1.0);
    }

    private static int chooseRadius(List<Option> options) {
        for (int radius : SEARCH_RADII) {
            long count = options.stream().filter(option -> option.distanceM() <= radius).count();
            if (count >= FEED_SIZE) {
                return radius;
            }
        }
        return SEARCH_RADII.getLast();
    }

    private static Comparator<Option> displayOrder(FeedSort sort) {
        Comparator<Option> order = switch (sort) {
            case DISTANCE -> Comparator.comparingDouble(Option::distanceM);
            case DISCOUNT_RATE -> Comparator.comparingDouble(FeedAssembler::discountScore).reversed();
        };
        return order.thenComparing(Option::campaignId);
    }

    private static List<Slot> layout(List<Option> allocation, List<Option> relevance) {
        Deque<Option> allocationQueue = new ArrayDeque<>(allocation);
        Deque<Option> relevanceQueue = new ArrayDeque<>(relevance);
        List<Slot> slots = new ArrayList<>();
        for (SlotType wanted : LAYOUT) {
            if (allocationQueue.isEmpty() && relevanceQueue.isEmpty()) {
                break;
            }
            Deque<Option> first = wanted == SlotType.ALLOCATION ? allocationQueue : relevanceQueue;
            Deque<Option> queue = first.isEmpty() ? (first == allocationQueue ? relevanceQueue : allocationQueue) : first;
            SlotType type = queue == allocationQueue ? SlotType.ALLOCATION : SlotType.RELEVANCE;
            slots.add(new Slot(queue.poll(), type, slots.size() + 1));
        }
        return slots;
    }
}
