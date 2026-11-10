package com.lunchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.campaign.service.CampaignDailyTransitionService;
import com.lunchcatch.campaign.service.CampaignStoreSummaryService;
import com.lunchcatch.global.measure.ScaleMeasureSettings;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 네 단계를 돌린 뒤 처음 상태로 되돌리는 복원(#1091)이 약속대로 동작하는지 본다. 측정은 한 번 잴 때마다 복원하므로
 * 복원이 어긋나면 회차마다 다른 데이터를 재게 된다.
 *
 * 진짜 서비스를 같은 순서(종료, 시작, 가게 요약, 하루 예산)로 불러 데이터가 네 단계를 실제로 통과하는지도 함께 본다.
 *
 * 클래스 단위 @Transactional 을 NOT_SUPPORTED 로 덮는다. 서비스가 자기 트랜잭션을 열고 준비한 행이 커밋되어 있어야 한다.
 *
 * 네 단계를 진짜로 여러 번 돌려 규모 200 에서도 1분이 넘게 걸린다. MEASURE_SCALE 이 있을 때만 돌아 ./gradlew check 의
 * 실행 시간을 늘리지 않는다. 측정을 돌리기 전에 이 클래스를 먼저 돌려 복원을 확인한다.
 */
@EnabledIfEnvironmentVariable(named = ScaleMeasureSettings.SCALE_ENV, matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignScaleRestoreIntegrationTest extends AbstractCampaignIntegrationTest {

    private static final int SCALE = 200;
    private static final LocalDate DATE = CampaignScaleFixture.BUSINESS_DATE;
    private static final String LIKE = CampaignScaleFixture.PREFIX + "%";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CampaignDailyTransitionService transitionService;
    @Autowired
    private CampaignStoreSummaryService summaryService;

    private CampaignScaleFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CampaignScaleFixture(jdbc);
        fixture.create(SCALE);
    }

    @AfterEach
    void tearDown() {
        fixture.deleteAll();
    }

    @Test
    void 오류가_없으면_네_단계가_정한_분포대로_처리한다() {
        fixture.captureBaseline();

        assertThat(transitionService.endExpired(DATE)).isEqualTo(30 + 10);
        assertThat(transitionService.startScheduled(DATE)).isEqualTo(20 + 20);
        // 기존 ACTIVE 20건은 시작 단계에서 ENDED 가 된다. 남는 ACTIVE 는 진행 중 70, 변경 대기 20, 새로 켠 40이다.
        assertThat(summaryService.prepare(DATE)).isEqualTo(70 + 20 + 40);
        assertThat(transitionService.applyPendingDailyBudgets(DATE)).isEqualTo(20);

        assertThat(count("select count(*) from store_campaign_summary s join store st on st.store_id = s.store_id"
                + " join owner o on o.owner_id = st.owner_id where o.email like ?")).isPositive();
        assertThat(count("select count(*) from campaign_status_log where campaign_id in"
                + " (select campaign_id from campaign where notice like ?)")).isPositive();
    }

    @Test
    void 복원하면_캠페인_상태와_로그와_가게_요약이_처음과_같다() {
        fixture.captureBaseline();
        runAllSteps();
        assertThat(fixture.matchesBaseline()).isFalse();

        int restored = fixture.restoreBaseline();

        assertThat(restored).isPositive();
        assertThat(fixture.matchesBaseline()).isTrue();
        assertThat(count("select count(*) from campaign_status_log where campaign_id in"
                + " (select campaign_id from campaign where notice like ?)")).isZero();
        assertThat(count("select count(*) from store_campaign_summary where store_id in"
                + " (select store_id from store where owner_id in (select owner_id from owner where email like ?))"))
                .isZero();
    }

    @Test
    void 복원한_뒤_다시_돌려도_결과가_같다() {
        fixture.captureBaseline();
        List<Integer> first = runAllSteps();

        fixture.restoreBaseline();
        List<Integer> second = runAllSteps();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 바뀐_것이_없으면_고친_캠페인은_0건이다() {
        fixture.captureBaseline();

        assertThat(fixture.restoreBaseline()).isZero();
        assertThat(fixture.matchesBaseline()).isTrue();
    }

    @Test
    void 오류_캠페인이_있어도_복원하고_다시_돌려도_결과가_같다() {
        fixture.applyErrorRate(0.01);
        fixture.captureBaseline();
        List<Integer> first = runAllSteps();

        fixture.restoreBaseline();
        assertThat(fixture.matchesBaseline()).isTrue();
        List<Integer> second = runAllSteps();

        assertThat(second).isEqualTo(first);
        // 오류 캠페인은 요약 준비가 건너뛰므로 오류가 없을 때의 130건보다 적다. 시작 대상의 오류는 그 캠페인을 ACTIVE 로 켜지 못하게 한다.
        assertThat(first.get(2)).isLessThan(70 + 20 + 40);
    }

    @Test
    void 처음_상태를_기억하지_않았으면_복원을_거부한다() {
        assertThatThrownBy(() -> fixture.restoreBaseline()).isInstanceOf(IllegalStateException.class);
    }

    // 종료, 시작, 가게 요약, 하루 예산 순서다. 돌려받은 개수를 같은 순서로 돌려준다.
    private List<Integer> runAllSteps() {
        int ended = transitionService.endExpired(DATE);
        int started = transitionService.startScheduled(DATE);
        int prepared = summaryService.prepare(DATE);
        int applied = transitionService.applyPendingDailyBudgets(DATE);
        return List.of(ended, started, prepared, applied);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class, LIKE);
        return value == null ? 0 : value;
    }
}
