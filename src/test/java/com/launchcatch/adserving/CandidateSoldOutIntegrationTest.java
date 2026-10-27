package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.adserving.engine.service.CandidateCache;
import com.launchcatch.global.config.ClockConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 쿠폰이 부르는 CandidateStateUpdater 빈부터 MySQL 의 ad_candidate 까지 본다.
 * 소진 표시가 소진 뒤 노출의 과금을 막아 돈이 걸린다. 쿠폰과 같이 트랜잭션 밖에서 부르려고 @Transactional 을 붙이지 않는다.
 */
@DisplayName("후보 소진 반영 통합")
class CandidateSoldOutIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long CAMPAIGN_ID = 101L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime SOLD_OUT_AT = LocalDateTime.of(2026, 10, 1, 11, 3, 20, 123_000_000);

    @Autowired CandidateStateUpdater candidateStateUpdater;
    @Autowired CandidateCache candidateCache;

    @BeforeEach
    void setUp() {
        doReturn(LocalDateTime.of(2026, 10, 1, 11, 5).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
    }

    private void insertCandidate(LocalDate date, long campaignId) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, created_at, updated_at)
                VALUES (?, ?, 55, '점심식당', 'KOREAN', 37.4979, 127.0276, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), 'SERVABLE', NOW(6), NOW(6))
                """, date, campaignId);
    }

    private LocalDateTime soldOutAt(LocalDate date, long campaignId) {
        return jdbcTemplate.queryForObject("SELECT sold_out_at FROM ad_candidate WHERE business_date = ? AND campaign_id = ?",
                LocalDateTime.class, date, campaignId);
    }

    @Test
    void 받은_영업일의_그_캠페인_후보에만_받은_소진_시각을_쓴다() {
        insertCandidate(TODAY, CAMPAIGN_ID);
        insertCandidate(TODAY.plusDays(1), CAMPAIGN_ID);
        insertCandidate(TODAY, 102L);

        candidateStateUpdater.markSoldOut(CAMPAIGN_ID, TODAY, SOLD_OUT_AT);

        assertThat(soldOutAt(TODAY, CAMPAIGN_ID)).isEqualTo(SOLD_OUT_AT);
        assertThat(soldOutAt(TODAY.plusDays(1), CAMPAIGN_ID)).isNull();
        assertThat(soldOutAt(TODAY, 102L)).isNull();
    }

    /*
     * 시계를 멈춰 두어 5초마다 다시 읽는 길로는 바뀌지 않는다. 소진이 메모리에 반영됐다면 Valkey 알림을 받아 그 후보를 다시 읽은 것이다(#1042).
     * 노출은 이 메모리로 소진을 판정한다. 늦게 반영되면 소진 뒤 노출이 과금된다.
     */
    @Test
    void 소진을_쓰면_Valkey_알림으로_서버_메모리의_후보도_소진이_된다() throws InterruptedException {
        insertCandidate(TODAY, CAMPAIGN_ID);
        assertThat(candidateCache.isSoldOut(TODAY, CAMPAIGN_ID)).isFalse();

        candidateStateUpdater.markSoldOut(CAMPAIGN_ID, TODAY, SOLD_OUT_AT);

        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!candidateCache.isSoldOut(TODAY, CAMPAIGN_ID) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(candidateCache.isSoldOut(TODAY, CAMPAIGN_ID)).isTrue();
    }

    @Test
    void 다시_불러도_처음_소진_시각이_남는다() {
        insertCandidate(TODAY, CAMPAIGN_ID);
        candidateStateUpdater.markSoldOut(CAMPAIGN_ID, TODAY, SOLD_OUT_AT);

        candidateStateUpdater.markSoldOut(CAMPAIGN_ID, TODAY, SOLD_OUT_AT.plusMinutes(10));

        assertThat(soldOutAt(TODAY, CAMPAIGN_ID)).isEqualTo(SOLD_OUT_AT);
    }

    @Test
    void 그_영업일의_후보가_없으면_예외_없이_아무것도_바꾸지_않는다() {
        insertCandidate(TODAY.plusDays(1), CAMPAIGN_ID);

        candidateStateUpdater.markSoldOut(CAMPAIGN_ID, TODAY, SOLD_OUT_AT);

        assertThat(soldOutAt(TODAY.plusDays(1), CAMPAIGN_ID)).isNull();
    }
}
