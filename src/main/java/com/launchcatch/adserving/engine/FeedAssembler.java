package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.filter.AudienceFilter;
import com.launchcatch.adserving.engine.filter.BudgetFilter;
import com.launchcatch.adserving.engine.filter.CandidateFilter;
import com.launchcatch.adserving.engine.filter.CategoryFilter;
import com.launchcatch.adserving.engine.filter.ExclusionFilter;
import com.launchcatch.adserving.engine.filter.FrequencyFilter;
import com.launchcatch.adserving.engine.score.CategorySignal;
import com.launchcatch.adserving.engine.score.DiscountSignal;
import com.launchcatch.adserving.engine.score.DistanceSignal;
import com.launchcatch.adserving.engine.score.RelevanceSignal;
import com.launchcatch.adserving.engine.score.ScoringPool;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * 그날 후보에서 한 사용자에게 내려줄 카드 10장을 고른다. DB 와 HTTP 를 모르는 계산이라 계층 밖에 둔다.
 * 배분 3장(1, 4, 7번)은 예산 소진 진행률 오름차순, 나머지는 관련성 점수 순이다(FR-093, FR-094, FR-096).
 * 점수 식과 빈 자리 처리는 #307 에서 정했고 설계서 4장에 있다.
 *
 * 피드 배정의 진입점은 assemble 하나다. 거르기 조건은 FILTERS, 관련성 점수 항목은 SIGNALS, 배분 순서는 AllocationOrder 가 갖는다.
 * 규칙을 더하려면 그 목록에 구현을 하나 더한다. 엔진을 통째로 바꿀 때는 FeedService 가 부르는 이 호출 하나를 인터페이스로 바꾼다(#978).
 */
public final class FeedAssembler {

    public static final int FEED_SIZE = 10;

    // 후보가 모자라면 이 순서로 넓힌다(FR-064 반경 확장). 마지막 값이 최대 반경이다.
    static final List<Integer> SEARCH_RADII_M = List.of(1000, 2000, 3000, 5000);

    public static final int MAX_RADIUS_M = SEARCH_RADII_M.getLast();

    // 배분 슬롯의 자리다. 1부터 센다.
    static final Set<Integer> ALLOCATION_POSITIONS = Set.of(1, 4, 7);

    // 후보 하나를 이 사용자에게 내보내려면 모두 통과해야 하는 조건들이다
    private static final List<CandidateFilter> FILTERS = List.of(new ExclusionFilter(), new CategoryFilter(),
            new AudienceFilter(), new FrequencyFilter(), new BudgetFilter());

    // 관련성 점수 = 거리 0.45 + 카테고리 선호도 0.35 + 할인 0.20 이다
    private static final List<RelevanceSignal> SIGNALS = List.of(new DistanceSignal(), new CategorySignal(),
            new DiscountSignal());

    private static final Comparator<Placed> NEAREST_FIRST =
            Comparator.comparingDouble(Placed::exactDistanceM).thenComparing(p -> p.candidate().campaignId());

    private FeedAssembler() {
    }

    /*
     * 후보가 10장 이상 모이는 가장 좁은 반경을 고르고 그 안에서 배분과 관련성 슬롯을 채운다.
     * 최대 반경에서도 10장이 안 되면 있는 만큼이고, 0장이면 cards 가 비고 radiusM 은 최대 반경이다.
     */
    public static Selection assemble(List<FeedCandidate> candidates, FeedContext context) {
        List<Placed> eligible = candidates.stream()
                .filter(c -> FILTERS.stream().allMatch(filter -> filter.accepts(c, context)))
                .map(c -> new Placed(c, distanceM(c, context.viewer()), SlotLabels.RELEVANCE))
                .filter(p -> p.exactDistanceM() <= p.candidate().targetRadiusM())
                .sorted(NEAREST_FIRST)
                .toList();

        int radius = MAX_RADIUS_M;
        for (int r : SEARCH_RADII_M) {
            if (eligible.stream().filter(p -> p.exactDistanceM() <= r).count() >= FEED_SIZE) {
                radius = r;
                break;
            }
        }
        int searchRadius = radius;
        List<Placed> pool = eligible.stream().filter(p -> p.exactDistanceM() <= searchRadius).toList();
        return new Selection(radius, layout(pool, context, radius));
    }

    /*
     * 배분 후보가 3장보다 적으면 빈 자리를 관련성으로 채우고, 전체가 10장보다 적으면 빈 자리 없이 앞으로 당긴다.
     * 정산에서 예산을 읽지 못했으면 진행률이 없어 10장 모두 관련성이다.
     */
    private static List<Placed> layout(List<Placed> pool, FeedContext context, int radius) {
        List<Placed> allocation = context.pacingAvailable()
                ? pool.stream()
                        .sorted(AllocationOrder.of(context))
                        .limit(ALLOCATION_POSITIONS.size())
                        .map(p -> p.in(SlotLabels.ALLOCATION))
                        .toList()
                : List.of();
        Set<Long> allocated = allocation.stream().map(p -> p.candidate().campaignId()).collect(Collectors.toSet());

        Map<DiscountType, Integer> maxDiscount = maxDiscountByType(pool);
        List<Placed> relevance = pool.stream()
                .filter(p -> !allocated.contains(p.candidate().campaignId()))
                .sorted(Comparator.<Placed>comparingDouble(p -> -relevanceScore(p, context, radius, maxDiscount))
                        .thenComparing(p -> p.candidate().campaignId()))
                .limit(FEED_SIZE - allocation.size())
                .sorted(displayOrder(context.sort(), maxDiscount))
                .toList();

        int total = allocation.size() + relevance.size();
        Iterator<Placed> allocationIt = allocation.iterator();
        Iterator<Placed> relevanceIt = relevance.iterator();
        List<Placed> cards = new ArrayList<>(total);
        for (int position = 1; position <= total; position++) {
            boolean allocationSlot = ALLOCATION_POSITIONS.contains(position);
            if (allocationSlot && allocationIt.hasNext()) {
                cards.add(allocationIt.next());
            } else if (relevanceIt.hasNext()) {
                cards.add(relevanceIt.next());
            } else {
                cards.add(allocationIt.next());
            }
        }
        return cards;
    }

    // 관련성 점수는 항목마다 0~1 의 값에 가중치를 곱해 더한 값이다
    static double relevanceScore(Placed p, FeedContext context, int radius, Map<DiscountType, Integer> maxDiscount) {
        ScoringPool pool = new ScoringPool(radius, maxDiscount);
        return SIGNALS.stream().mapToDouble(signal -> signal.weight() * signal.score(p, context, pool)).sum();
    }

    static double discountScore(FeedCandidate c, Map<DiscountType, Integer> maxDiscount) {
        return DiscountSignal.of(c, maxDiscount);
    }

    private static Map<DiscountType, Integer> maxDiscountByType(List<Placed> pool) {
        Map<DiscountType, Integer> max = new EnumMap<>(DiscountType.class);
        pool.forEach(p -> max.merge(p.candidate().discountType(), p.candidate().discountValue(), Math::max));
        return max;
    }

    // 관련성 슬롯에 고른 카드를 사용자의 필터 정렬로 줄 세운다(FR-067).
    private static Comparator<Placed> displayOrder(FeedSort sort, Map<DiscountType, Integer> maxDiscount) {
        if (sort == FeedSort.DISCOUNT_RATE) {
            return Comparator.<Placed>comparingDouble(p -> -discountScore(p.candidate(), maxDiscount))
                    .thenComparing(p -> p.candidate().campaignId());
        }
        return NEAREST_FIRST;
    }

    private static double distanceM(FeedCandidate candidate, Viewer viewer) {
        return GeoDistance.meters(viewer.latitude(), viewer.longitude(),
                candidate.latitude(), candidate.longitude());
    }

    // 카드를 받는 사용자의 위치와 노출 대상 판정 값이다.
    public record Viewer(double latitude, double longitude, Gender gender, AgeGroup ageGroup) {
    }

    public record Selection(int radiusM, List<Placed> cards) {
    }

    public record Placed(FeedCandidate candidate, double exactDistanceM, String slotType) {

        public int distanceM() {
            return (int) Math.round(exactDistanceM);
        }

        Placed in(String slot) {
            return new Placed(candidate, exactDistanceM, slot);
        }
    }
}
