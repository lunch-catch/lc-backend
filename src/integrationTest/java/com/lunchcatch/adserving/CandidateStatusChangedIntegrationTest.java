package com.lunchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.lunchcatch.adserving.engine.entity.AdCandidate;
import com.lunchcatch.adserving.engine.repository.AdCandidateRepository;
import com.lunchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.global.geo.BoundingBox;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 캠페인의 상태 변경 이벤트를 입구로 MySQL 의 ad_candidate 까지 본다(#565).
 * 멈춘 캠페인이 그날 피드에 계속 나가면 정산은 과금을 멈췄는데 노출만 나가 돈이 걸린다. 상태 반영은 직접 쓴 쿼리라 여기서만 실행된다.
 */
@DisplayName("캠페인 상태 변경의 후보 반영 통합")
class CandidateStatusChangedIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long CAMPAIGN_ID = 101L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime PAUSED_AT = LocalDateTime.of(2026, 10, 1, 11, 0);

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired AdCandidateRepository adCandidateRepository;

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

    private String stateOf(LocalDate date, long campaignId) {
        return jdbcTemplate.queryForObject("SELECT campaign_state FROM ad_candidate WHERE business_date = ? AND campaign_id = ?",
                String.class, date, campaignId);
    }

    // 캠페인의 상태 전이는 campaign 의 트랜잭션 안에서 발행된다
    private void publishInTransaction(CampaignStatusChangedEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private boolean servableNow() {
        return adCandidateRepository.findServableWithin(TODAY, BoundingBox.around(37.4979, 127.0276, 5000)).stream()
                .map(AdCandidate::getCampaignId).anyMatch(id -> id == CAMPAIGN_ID);
    }

    @Test
    void 멈추면_그날_그_캠페인_후보만_피드에서_빠지고_재개하면_다시_나온다() {
        insertCandidate(TODAY, CAMPAIGN_ID);
        insertCandidate(TODAY.plusDays(1), CAMPAIGN_ID);
        insertCandidate(TODAY, 102L);

        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, false, PAUSED_AT));

        assertThat(stateOf(TODAY, CAMPAIGN_ID)).isEqualTo("PAUSED");
        assertThat(stateOf(TODAY.plusDays(1), CAMPAIGN_ID)).isEqualTo("SERVABLE");
        assertThat(stateOf(TODAY, 102L)).isEqualTo("SERVABLE");
        assertThat(servableNow()).isFalse();

        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, true, PAUSED_AT.plusMinutes(30)));

        assertThat(stateOf(TODAY, CAMPAIGN_ID)).isEqualTo("SERVABLE");
        assertThat(servableNow()).isTrue();
    }

    @Test
    void 더_이른_전이가_늦게_와도_상태가_되돌아가지_않는다() {
        insertCandidate(TODAY, CAMPAIGN_ID);
        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, false, PAUSED_AT));

        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, true, PAUSED_AT.minusMinutes(10)));
        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, true, PAUSED_AT));

        assertThat(stateOf(TODAY, CAMPAIGN_ID)).isEqualTo("PAUSED");
    }

    @Test
    void 소진된_후보는_재개돼도_피드에_나오지_않는다() {
        insertCandidate(TODAY, CAMPAIGN_ID);
        jdbcTemplate.update("UPDATE ad_candidate SET sold_out_at = '2026-10-01 11:02:00' WHERE campaign_id = ?", CAMPAIGN_ID);
        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, false, PAUSED_AT));

        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, true, PAUSED_AT.plusMinutes(30)));

        assertThat(stateOf(TODAY, CAMPAIGN_ID)).isEqualTo("SERVABLE");
        assertThat(servableNow()).isFalse();
    }

    @Test
    void 캠페인의_상태_전이가_되돌아가면_후보의_상태도_되돌아간다() {
        insertCandidate(TODAY, CAMPAIGN_ID);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, false, PAUSED_AT));
            throw new IllegalStateException("캠페인 상태 전이 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(stateOf(TODAY, CAMPAIGN_ID)).isEqualTo("SERVABLE");
    }

    @Test
    void 그날_후보가_없어도_예외_없이_넘어간다() {
        publishInTransaction(new CampaignStatusChangedEvent(CAMPAIGN_ID, TODAY, false, PAUSED_AT));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ad_candidate", Long.class)).isZero();
    }
}
