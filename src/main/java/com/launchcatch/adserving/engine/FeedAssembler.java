package com.launchcatch.adserving.engine;

import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.geo.GeoDistance;
import java.util.List;

/*
 * 후보 스냅샷에서 이 사용자에게 내려줄 카드 10장을 고른다(96, 97, 99행).
 * 먼저 노출 대상, 노출 반경, 피드 필터, 그날 이력, 예산으로 거른다.
 * 그다음 탐색 반경을 정하고 슬롯 배정은 SlotAllocator 에 맡긴다.
 */
public final class FeedAssembler {

    // 후보가 모자라면 이 순서로 탐색 반경을 넓힌다(67행)
    static final List<Integer> SEARCH_RADII_M = List.of(1000, 2000, 3000, 5000);

    /*
     * 탐색 반경의 최댓값이다.
     * service 가 후보를 읽을 상자를 이 값으로 만든다
     */
    public static final int MAX_SEARCH_RADIUS_M = 5000;

    // 피드 한 번의 카드 수다
    static final int FEED_SIZE = 10;

    private final SlotAllocator slotAllocator = new SlotAllocator();

    public FeedPlan assemble(FeedInput input) {
        Viewer viewer = input.viewer();
        List<Placed> eligible = input.candidates().stream()
                .filter(candidate -> !input.excludedCampaignIds().contains(candidate.campaignId()))
                .filter(candidate -> input.categories().isEmpty() || input.categories().contains(candidate.categoryCode()))
                .filter(candidate -> targets(candidate, viewer))
                .filter(candidate -> !input.budget().excludes(candidate.campaignId()))
                .map(candidate -> new Placed(candidate, distance(candidate, viewer)))
                .filter(placed -> placed.distanceM() <= placed.candidate().targetRadius())
                .toList();

        int radius = searchRadius(eligible);
        List<Placed> pool = eligible.stream().filter(placed -> placed.distanceM() <= radius).toList();
        return new FeedPlan(radius, slotAllocator.allocate(pool, radius, input));
    }

    /*
     * 적격 후보가 10장 이상인 가장 작은 반경이다.
     * 끝까지 모자라면 가장 큰 반경이다
     */
    private static int searchRadius(List<Placed> eligible) {
        for (int radius : SEARCH_RADII_M) {
            long within = eligible.stream().filter(placed -> placed.distanceM() <= radius).count();
            if (within >= FEED_SIZE) {
                return radius;
            }
        }
        return MAX_SEARCH_RADIUS_M;
    }

    // 성별 OTHER 는 대상 성별이 ALL 인 캠페인만 받는다
    private static boolean targets(FeedCandidate candidate, Viewer viewer) {
        boolean gender = candidate.targetGender() == TargetGender.ALL
                || candidate.targetGender().name().equals(viewer.gender().name());
        boolean ageGroup = candidate.targetAgeGroups().stream()
                .anyMatch(group -> group.name().equals(viewer.ageGroup().name()));
        return gender && ageGroup;
    }

    private static double distance(FeedCandidate candidate, Viewer viewer) {
        return GeoDistance.meters(viewer.latitude(), viewer.longitude(), candidate.latitude(), candidate.longitude());
    }

    // 거른 후보와 사용자 위치에서의 거리다
    record Placed(FeedCandidate candidate, double distanceM) {
    }
}
