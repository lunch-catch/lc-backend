package com.launchcatch.adserving.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.adserving.feed.StoreAudienceCounter.Point;
import com.launchcatch.adserving.feed.StoreAudienceCounter.Profile;
import com.launchcatch.adserving.feed.StoreAudienceCounter.Store;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.member.contract.Gender;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("가게별 반경 안의 대상 인원 세기")
class StoreAudienceCounterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final Store STORE = new Store(55L, 37.4979, 127.0276);
    // 위도 0.001도는 약 111m 다
    private static final Point AT_STORE = new Point(37.4979, 127.0276);
    private static final Point NORTH_800M = new Point(37.5051, 127.0276);
    private static final Point NORTH_2500M = new Point(37.5204, 127.0276);
    private static final Point NORTH_5KM = new Point(37.5429, 127.0276);
    private static final Profile MALE_20S = new Profile(Gender.MALE, com.launchcatch.member.contract.AgeGroup.AGE_20S);

    private static int count(List<StoreAudienceCell> cells, int radius, TargetGender gender, AgeGroup age) {
        return cells.stream()
                .filter(c -> c.storeId() == 55L && c.targetRadius() == radius && c.gender() == gender && c.ageGroup() == age)
                .mapToInt(StoreAudienceCell::memberCount).findFirst().orElseThrow();
    }

    @Test
    void 가게마다_반경_4단계_x_성별_3_x_연령대_4_의_48칸을_인원이_0이어도_만든다() {
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(STORE), Map.of(), Map.of());

        assertThat(cells).hasSize(48).allMatch(c -> c.memberCount() == 0 && c.businessDate().equals(TODAY));
    }

    // 반경은 포함 관계다. 800m 의 회원은 1000, 2000, 3000m 칸에도 든다
    @Test
    void 반경_안의_회원은_그보다_넓은_반경_칸에도_센다() {
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(STORE),
                Map.of(1L, List.of(NORTH_800M), 2L, List.of(NORTH_2500M), 3L, List.of(NORTH_5KM)),
                Map.of(1L, MALE_20S, 2L, MALE_20S, 3L, MALE_20S));

        assertThat(count(cells, 500, TargetGender.MALE, AgeGroup.AGE_20S)).isZero();
        assertThat(count(cells, 1000, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(1);
        assertThat(count(cells, 2000, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(1);
        assertThat(count(cells, 3000, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(2);
    }

    // 7일 동안 여러 곳에서 접속해도 한 칸에 한 번이다. 한 곳이라도 반경 안이면 센다
    @Test
    void 여러_위치에서_접속한_회원은_한_곳이라도_반경_안이면_칸마다_한_번_센다() {
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(STORE),
                Map.of(1L, List.of(NORTH_5KM, AT_STORE, NORTH_800M)), Map.of(1L, MALE_20S));

        assertThat(count(cells, 500, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(1);
        assertThat(count(cells, 3000, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(1);
    }

    // ALL 은 남성과 여성의 합이 아니다. OTHER 는 ALL 칸에만 든다(#218)
    @Test
    void ALL_칸은_성별과_관계없이_세고_OTHER_는_ALL_칸에만_든다() {
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(STORE),
                Map.of(1L, List.of(AT_STORE), 2L, List.of(AT_STORE), 3L, List.of(AT_STORE)),
                Map.of(1L, MALE_20S, 2L, new Profile(Gender.FEMALE, com.launchcatch.member.contract.AgeGroup.AGE_20S),
                        3L, new Profile(Gender.OTHER, com.launchcatch.member.contract.AgeGroup.AGE_20S)));

        assertThat(count(cells, 500, TargetGender.MALE, AgeGroup.AGE_20S)).isEqualTo(1);
        assertThat(count(cells, 500, TargetGender.FEMALE, AgeGroup.AGE_20S)).isEqualTo(1);
        assertThat(count(cells, 500, TargetGender.ALL, AgeGroup.AGE_20S)).isEqualTo(3);
    }

    @Test
    void 프로필이_없는_회원은_세지_않는다() {
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(STORE),
                Map.of(1L, List.of(AT_STORE)), Map.of());

        assertThat(cells).allMatch(c -> c.memberCount() == 0);
    }

    // 격자 경계 근처의 회원도 빠뜨리지 않는다. 가게와 다른 격자에 있어도 반경 안이면 센다
    @Test
    void 격자가_달라도_반경_안이면_센다() {
        Store edge = new Store(77L, 37.5299, 127.0299);
        List<StoreAudienceCell> cells = StoreAudienceCounter.count(TODAY, List.of(edge),
                Map.of(1L, List.of(new Point(37.5301, 127.0301))), Map.of(1L, MALE_20S));

        assertThat(cells.stream().filter(c -> c.targetRadius() == 500 && c.gender() == TargetGender.MALE
                && c.ageGroup() == AgeGroup.AGE_20S).mapToInt(StoreAudienceCell::memberCount).sum()).isEqualTo(1);
    }
}
