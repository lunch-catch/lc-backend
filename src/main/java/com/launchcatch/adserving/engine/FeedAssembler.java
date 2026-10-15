package com.launchcatch.adserving.engine;

import com.launchcatch.adserving.engine.entity.TargetGender;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/*
 * 그날 후보에서 한 사용자에게 내려줄 카드를 고른다. DB 와 HTTP 를 모르는 계산이라 계층 밖에 둔다.
 * 지금은 후보 조건으로 거른 뒤 거리순으로 자른다. 배분과 관련성 슬롯, 예산과 노출 상한, 하루 2회와 세션 1회는 아직 없다(#236 의 범위 결정).
 */
public final class FeedAssembler {

    public static final int FEED_SIZE = 10;

    // 후보가 모자라면 이 순서로 넓힌다(FR-064 반경 확장). 마지막 값이 최대 반경이다.
    static final List<Integer> SEARCH_RADII_M = List.of(1000, 2000, 3000, 5000);

    public static final int MAX_RADIUS_M = SEARCH_RADII_M.getLast();

    private static final Comparator<Placed> NEAREST_FIRST =
            Comparator.comparingDouble(Placed::exactDistanceM).thenComparing(p -> p.candidate().campaignId());

    private FeedAssembler() {
    }

    /*
     * 후보가 10장 이상 모이는 가장 좁은 반경에서 가까운 순으로 10장을 고른다.
     * 최대 반경에서도 10장이 안 되면 있는 만큼 돌려주고, 0장이면 cards 가 비고 radiusM 은 최대 반경이다.
     * categories 가 비어 있으면 업종을 거르지 않는다(FR-067 예외).
     */
    public static Selection assemble(List<FeedCandidate> candidates, Viewer viewer,
                                     Set<Long> excludedCampaignIds, Set<String> categories) {
        List<Placed> eligible = candidates.stream()
                .filter(c -> !excludedCampaignIds.contains(c.campaignId()))
                .filter(c -> categories.isEmpty() || categories.contains(c.categoryCode()))
                .filter(c -> targetsGender(c.targetGender(), viewer.gender()))
                .filter(c -> viewer.ageGroup() != null && c.targetAgeGroups().contains(viewer.ageGroup().name()))
                .map(c -> new Placed(c, distanceM(c, viewer)))
                .filter(p -> p.exactDistanceM() <= p.candidate().targetRadiusM())
                .sorted(NEAREST_FIRST)
                .toList();

        for (int radius : SEARCH_RADII_M) {
            List<Placed> within = eligible.stream().filter(p -> p.exactDistanceM() <= radius).toList();
            if (within.size() >= FEED_SIZE) {
                return new Selection(radius, within.subList(0, FEED_SIZE));
            }
        }
        List<Placed> within = eligible.stream().filter(p -> p.exactDistanceM() <= MAX_RADIUS_M).toList();
        return new Selection(MAX_RADIUS_M, within);
    }

    private static boolean targetsGender(TargetGender target, Gender gender) {
        return target == TargetGender.ALL || (gender != null && target.name().equals(gender.name()));
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

    public record Placed(FeedCandidate candidate, double exactDistanceM) {

        public int distanceM() {
            return (int) Math.round(exactDistanceM);
        }
    }
}
