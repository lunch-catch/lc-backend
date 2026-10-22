package com.launchcatch.adserving.feed;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.Gender;
import java.time.LocalDate;
import java.util.ArrayList;
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

    // 캠페인이 고를 수 있는 점주 반경(m)이다. 가장 넓은 반경까지만 접속 위치를 본다
    static final List<Integer> RADII_M = List.of(500, 1000, 2000, 3000);
    private static final int MAX_RADIUS_M = 3000;

    // 접속 위치를 나누는 격자 한 칸의 크기(도)다. 가게마다 반경 3km 상자에 걸치는 격자만 본다
    private static final double GRID_DEGREES = 0.05;
    private static final double METERS_PER_DEGREE_LATITUDE = 111_320;

    private StoreAudienceCounter() {
    }

    public static List<StoreAudienceCell> count(LocalDate businessDate, List<Store> stores,
                                                Map<Long, List<Point>> visitsByMember, Map<Long, Profile> profiles) {
        Map<Long, List<Located>> grid = new HashMap<>();
        visitsByMember.forEach((memberId, points) -> {
            Profile profile = profiles.get(memberId);
            if (profile == null) {
                return;
            }
            for (Point point : points) {
                grid.computeIfAbsent(cell(point.latitude(), point.longitude()), k -> new ArrayList<>())
                        .add(new Located(memberId, profile, point));
            }
        });

        List<StoreAudienceCell> cells = new ArrayList<>(stores.size() * 48);
        for (Store store : stores) {
            cells.addAll(countStore(businessDate, store, grid));
        }
        return cells;
    }

    private static List<StoreAudienceCell> countStore(LocalDate businessDate, Store store, Map<Long, List<Located>> grid) {
        // 회원마다 가게에서 가장 가까운 접속 위치의 거리다
        Map<Long, Double> nearest = new HashMap<>();
        Map<Long, Profile> profileOf = new HashMap<>();
        double latSpan = MAX_RADIUS_M / METERS_PER_DEGREE_LATITUDE;
        double lngSpan = latSpan / Math.cos(Math.toRadians(store.latitude()));
        for (long row = index(store.latitude() - latSpan); row <= index(store.latitude() + latSpan); row++) {
            for (long col = index(store.longitude() - lngSpan); col <= index(store.longitude() + lngSpan); col++) {
                for (Located v : grid.getOrDefault(key(row, col), List.of())) {
                    double distance = GeoDistance.meters(store.latitude(), store.longitude(), v.point().latitude(), v.point().longitude());
                    if (distance <= MAX_RADIUS_M) {
                        nearest.merge(v.memberId(), distance, Math::min);
                        profileOf.put(v.memberId(), v.profile());
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

    public record Point(double latitude, double longitude) {
    }

    // 회원의 성별과 연령대다. 둘 중 하나라도 없으면 부르는 쪽이 넘기지 않는다
    public record Profile(Gender gender, com.launchcatch.member.contract.AgeGroup ageGroup) {
    }

    private record Located(long memberId, Profile profile, Point point) {
    }
}
