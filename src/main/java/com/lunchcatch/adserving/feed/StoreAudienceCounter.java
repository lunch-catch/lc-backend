package com.lunchcatch.adserving.feed;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.StoreAudienceCell;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.global.geo.BoundingBox;
import com.lunchcatch.global.geo.GeoDistance;
import com.lunchcatch.member.contract.Gender;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 가게마다 점주 반경 4단계 x 성별 x 연령대 칸으로 최근 접속 회원을 센다(FR-042, FR-099 (10)). DB 와 HTTP 에 기대지 않는 순수 계산이다.
 * 반경은 포함 관계이고, 회원은 접속한 위치 가운데 한 곳이라도 반경 안이면 칸마다 한 번 센다. 인원이 0 인 칸도 만들어 전날 값을 덮는다.
 * 성별 칸은 캠페인의 타겟 성별이다. ALL 은 프로필이 있는 모든 회원이라 MALE 과 FEMALE 의 합이 아니고 OTHER 는 ALL 칸에만 든다(#218).
 */
public final class StoreAudienceCounter {

    /*
     * 캠페인이 고를 수 있는 점주 반경(m)이다.
     * 가장 넓은 반경까지만 접속 위치를 본다.
     */
    static final List<Integer> RADII_M = List.of(500, 1000, 2000, 3000);
    private static final int MAX_RADIUS_M = 3000;

    // 가게 하나의 칸 수다. 반경 4 x 성별 3 x 연령대 4 = 48칸이다
    public static final int CELLS_PER_STORE = RADII_M.size() * TargetGender.values().length * AgeGroup.values().length;

    /*
     * 접속 위치를 나누는 격자 한 칸의 크기(도)다.
     * 가게마다 반경 3km 상자에 걸치는 격자만 본다.
     */
    private static final double GRID_DEGREES = 0.05;

    private StoreAudienceCounter() {
    }

    public static List<StoreAudienceCell> count(LocalDate businessDate, List<Store> stores, VisitGrid grid,
                                                Map<Long, Profile> profiles) {
        List<StoreAudienceCell> cells = new ArrayList<>(stores.size() * CELLS_PER_STORE);
        for (Store store : stores) {
            cells.addAll(countStore(businessDate, store, grid, profiles));
        }
        return cells;
    }

    private static List<StoreAudienceCell> countStore(LocalDate businessDate, Store store, VisitGrid grid,
                                                      Map<Long, Profile> profiles) {
        // 회원마다 가게에서 가장 가까운 접속 위치의 거리다
        Map<Long, Double> nearest = new HashMap<>();
        Map<Long, Profile> profileOf = new HashMap<>();
        // 거리 계산과 같은 반지름으로 원을 반드시 덮는 상자다. 상자가 원보다 작으면 격자선 너머의 회원이 빠진다(#774)
        BoundingBox box = BoundingBox.around(store.latitude(), store.longitude(), MAX_RADIUS_M);
        for (long row = index(box.minLatitude()); row <= index(box.maxLatitude()); row++) {
            for (long col = index(box.minLongitude()); col <= index(box.maxLongitude()); col++) {
                for (Located v : grid.cells.getOrDefault(key(row, col), List.of())) {
                    Profile profile = profiles.get(v.memberId());
                    if (profile == null) {
                        continue;
                    }
                    double distance = GeoDistance.meters(store.latitude(), store.longitude(), v.latitude(), v.longitude());
                    if (distance <= MAX_RADIUS_M) {
                        nearest.merge(v.memberId(), distance, Math::min);
                        profileOf.put(v.memberId(), profile);
                    }
                }
            }
        }

        Map<String, Integer> counts = new HashMap<>();
        nearest.forEach((memberId, distance) -> {
            Profile profile = profileOf.get(memberId);
            AgeGroup age = AgeGroup.valueOf(profile.ageGroup().name());
            Set<TargetGender> genders = new HashSet<>(List.of(TargetGender.ALL));
            if (profile.gender() == Gender.MALE) {
                genders.add(TargetGender.MALE);
            } else if (profile.gender() == Gender.FEMALE) {
                genders.add(TargetGender.FEMALE);
            }
            for (int radius : RADII_M) {
                if (distance <= radius) {
                    genders.forEach(g -> counts.merge(radius + ":" + g + ":" + age, 1, Integer::sum));
                }
            }
        });

        List<StoreAudienceCell> cells = new ArrayList<>(48);
        for (int radius : RADII_M) {
            for (TargetGender gender : TargetGender.values()) {
                for (AgeGroup age : AgeGroup.values()) {
                    cells.add(new StoreAudienceCell(store.storeId(), radius, gender, age,
                            counts.getOrDefault(radius + ":" + gender + ":" + age, 0), businessDate));
                }
            }
        }
        return cells;
    }

    private static long index(double degrees) {
        return (long) Math.floor(degrees / GRID_DEGREES);
    }

    private static long cell(double latitude, double longitude) {
        return key(index(latitude), index(longitude));
    }

    private static long key(long row, long col) {
        return row * 1_000_000L + col;
    }

    public record Store(long storeId, double latitude, double longitude) {
    }

    /*
     * 회원의 성별과 연령대다.
     * 둘 중 하나라도 없으면 부르는 쪽이 넘기지 않는다.
     */
    public record Profile(Gender gender, com.lunchcatch.member.contract.AgeGroup ageGroup) {
    }

    /*
     * 최근 접속 위치를 격자 칸으로 나눠 담는다. 저장소가 한 행씩 넘기는 대로 넣어 접속 위치를 한 벌만 든다(#774).
     * 프로필은 담지 않고 셀 때 회원 ID 로 찾는다. 프로필을 읽으려면 회원 ID 가 먼저 다 모여야 해서다.
     */
    public static final class VisitGrid {

        private final Map<Long, List<Located>> cells = new HashMap<>();
        private final Set<Long> memberIds = new HashSet<>();

        public void add(long memberId, double latitude, double longitude) {
            cells.computeIfAbsent(cell(latitude, longitude), k -> new ArrayList<>()).add(new Located(memberId, latitude, longitude));
            memberIds.add(memberId);
        }

        public Set<Long> memberIds() {
            return Collections.unmodifiableSet(memberIds);
        }
    }

    private record Located(long memberId, double latitude, double longitude) {
    }
}
