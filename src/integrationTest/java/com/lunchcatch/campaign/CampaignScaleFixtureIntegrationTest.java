package com.lunchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.campaign.CampaignScaleFixture.Kind;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 규모 측정용 데이터 생성기(#1091)가 약속한 데이터를 만드는지 본다. 측정 결과가 이 데이터의 모양을 전제로 읽히므로
 * 측정을 돌리기 전에 분포와 결정성, 오류 비율, 정리를 먼저 확인한다.
 *
 * 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮는다. 생성기가 커밋된 행을 만들기 때문이다.
 * 규모 1000 은 행이 수천 건이라 1초 안팎이다.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignScaleFixtureIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final int SCALE = 1000;
    private static final String LIKE = CampaignScaleFixture.PREFIX + "%";

    @Autowired
    private JdbcTemplate jdbc;

    private CampaignScaleFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CampaignScaleFixture(jdbc);
    }

    @AfterEach
    void tearDown() {
        fixture.deleteAll();
    }

    @Test
    void 종류별_개수가_정한_비율과_같다() {
        fixture.create(SCALE);

        assertThat(fixture.campaigns()).hasSize(SCALE);
        assertThat(fixture.count(Kind.END_ACTIVE)).isEqualTo(150);
        assertThat(fixture.count(Kind.END_SCHEDULED)).isEqualTo(50);
        assertThat(fixture.count(Kind.START_PLAIN)).isEqualTo(100);
        assertThat(fixture.count(Kind.START_REPLACE)).isEqualTo(100);
        assertThat(fixture.count(Kind.REPLACED_ACTIVE)).isEqualTo(100);
        assertThat(fixture.count(Kind.RUNNING)).isEqualTo(350);
        assertThat(fixture.count(Kind.PENDING_BUDGET)).isEqualTo(100);
        assertThat(fixture.count(Kind.PAUSED)).isEqualTo(50);
        assertThat(fixture.storeCount()).isEqualTo(SCALE - 100);
    }

    @Test
    void 상태와_날짜가_종류에_맞고_가게당_ACTIVE_는_하나다() {
        fixture.create(SCALE);

        Map<String, Integer> byStatus = jdbc.query(
                "select status, count(*) from campaign where notice like ? group by status",
                (rs, row) -> Map.entry(rs.getString(1), rs.getInt(2)), LIKE)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(byStatus).containsEntry("ACTIVE", 150 + 100 + 350 + 100)
                .containsEntry("SCHEDULED", 50 + 100 + 100)
                .containsEntry("PAUSED", 50);

        assertThat(count("select count(*) from (select store_id from campaign where notice like ?"
                + " and status = 'ACTIVE' group by store_id having count(*) > 1) t")).isZero();
        // START_REPLACE 100건은 같은 가게에 기존 ACTIVE 가 있다.
        assertThat(count("select count(*) from campaign s join campaign a on a.store_id = s.store_id"
                + " and a.status = 'ACTIVE' where s.notice like ? and s.status = 'SCHEDULED'"
                + " and s.start_date = '2026-11-10'")).isEqualTo(100);
        assertThat(count("select count(*) from campaign where notice like ? and status = 'ACTIVE'"
                + " and end_date < '2026-11-10'")).isEqualTo(150);
        assertThat(count("select count(*) from campaign where notice like ? and status = 'SCHEDULED'"
                + " and end_date < '2026-11-10'")).isEqualTo(50);
        assertThat(count("select count(*) from campaign where notice like ?"
                + " and pending_daily_budget is not null")).isEqualTo(100);
    }

    @Test
    void 오류가_없으면_MENU_할인은_모두_자기_가게_메뉴를_가리킨다() {
        fixture.create(SCALE);

        assertThat(count("select count(*) from campaign where notice like ?"
                + " and discount_target_type = 'MENU'")).isBetween(400, 600);
        assertThat(count("select count(*) from campaign c join store_menu m on m.store_menu_id = c.target_menu_id"
                + " where c.notice like ? and m.store_id <> c.store_id")).isZero();
        assertThat(fixture.errorCampaignIds()).isEmpty();
    }

    @Test
    void 같은_규모로_다시_만들면_같은_데이터가_나온다() {
        fixture.create(SCALE);
        List<String> first = signature();

        fixture.create(SCALE);
        List<String> second = signature();

        assertThat(first).hasSize(SCALE).isEqualTo(second);
    }

    @Test
    void 오류_비율만큼_다른_가게의_메뉴를_가리키는_캠페인을_만든다() {
        fixture.create(SCALE);
        String mismatch = "select count(*) from campaign c join store_menu m on m.store_menu_id = c.target_menu_id"
                + " where c.notice like ? and m.store_id <> c.store_id";

        fixture.applyErrorRate(0.01);
        assertThat(count(mismatch)).isEqualTo(10);
        assertThat(fixture.errorCampaignIds()).hasSize(10);
        var onePercent = fixture.errorCampaignIds();

        // 비율을 낮추면 앞선 오류가 먼저 되돌아가고, 0.1% 의 오류 캠페인은 1% 에 들어 있던 것이다.
        fixture.applyErrorRate(0.001);
        assertThat(count(mismatch)).isEqualTo(1);
        assertThat(onePercent).containsAll(fixture.errorCampaignIds());

        fixture.applyErrorRate(0);
        assertThat(count(mismatch)).isZero();
        assertThat(fixture.errorCampaignIds()).isEmpty();
    }

    @Test
    void 오류_캠페인은_진행_중이거나_시작_대상인_MENU_할인에만_생긴다() {
        fixture.create(SCALE);
        fixture.applyErrorRate(0.01);

        var errorKinds = fixture.campaigns().stream()
                .filter(ref -> fixture.errorCampaignIds().contains(ref.campaignId()))
                .toList();
        assertThat(errorKinds).hasSize(10).allSatisfy(ref -> {
            assertThat(ref.menuDiscount()).isTrue();
            assertThat(ref.kind()).isIn(Kind.RUNNING, Kind.START_PLAIN, Kind.START_REPLACE);
        });
    }

    @Test
    void 후보보다_많은_오류는_거부한다() {
        fixture.create(SCALE);

        assertThatThrownBy(() -> fixture.applyErrorRate(1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.applyErrorRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지우고_나면_만든_행이_남지_않는다() {
        fixture.create(SCALE);
        fixture.applyErrorRate(0.01);

        fixture.deleteAll();

        assertThat(count("select count(*) from campaign where notice like ?")).isZero();
        assertThat(count("select count(*) from store where name like ?")).isZero();
        assertThat(count("select count(*) from owner where email like ?")).isZero();
        assertThat(count("select count(*) from store_menu where name like ?")).isZero();
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class, LIKE);
        return value == null ? 0 : value;
    }

    // 번호 순으로 캠페인의 내용을 문자열로 만든다. 자동 증가 번호는 빼서 다시 만들어도 같게 한다.
    private List<String> signature() {
        return jdbc.query(
                "select notice, status, discount_target_type, discount_type, discount_value, issue_quantity,"
                        + " usable_start_time, usable_end_time, daily_budget, pending_daily_budget,"
                        + " start_date, end_date from campaign where notice like ?"
                        + " order by cast(substring(notice, 15) as unsigned)",
                (rs, row) -> String.join("|",
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12)),
                LIKE);
    }
}
