package com.lunchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.analytics.batch.FraudMetrics;
import com.lunchcatch.analytics.batch.InvalidMetrics;
import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.batch.NotBilledMetrics;
import com.lunchcatch.analytics.batch.NotBilledReason;
import com.lunchcatch.analytics.batch.job.DailyAggregationJob;
import com.lunchcatch.analytics.batch.repository.FraudAnalyticsRepository;
import com.lunchcatch.analytics.batch.repository.InvalidAnalyticsRepository;
import com.lunchcatch.analytics.batch.repository.NotBilledAnalyticsRepository;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.config.ClockConfig;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 무효 노출, 미과금, 부정 경고의 일별 집계를 실제 MySQL 로 끝까지 확인한다. 노출과 과금 판정 행을 심고 01:00 작업의
 * run() 으로 집계한 뒤 세 표의 행과 A-09, A-10 응답을 본다. 입구는 시각(배치)과 HTTP 둘이다.
 *
 * 노출은 한 영업일 안에서 캠페인 하나에 유효 103건(회원 둘, 쿠폰 소진 뒤 3건 포함)과 무효 120건을, 없는 캠페인에
 * 무효 100건을 심는다. 경고 경계는 단위 테스트가 보고 여기서는 두 경고가 실제 쿼리를 지나 나오는지를 본다.
 */
class FraudAggregationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long OWNER = 971_000L;
    private static final long CAMPAIGN = 971_100L;
    private static final long UNKNOWN_CAMPAIGN = 971_900L;
    private static final long HEAVY_MEMBER = 971_001L;
    private static final long OTHER_MEMBER = 971_002L;
    private static final long INVALID_MEMBER = 971_003L;

    private final LocalDate today = LocalDate.now(ClockConfig.ZONE);
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate twoDaysAgo = today.minusDays(2);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private DailyAggregationJob job;
    @Autowired
    private InvalidAnalyticsRepository invalidRepository;
    @Autowired
    private NotBilledAnalyticsRepository notBilledRepository;
    @Autowired
    private FraudAnalyticsRepository fraudRepository;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log");
        jdbc.update("DELETE FROM aggregation_pending_date");
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM daily_invalid_analytics");
        jdbc.update("DELETE FROM daily_campaign_not_billed_analytics");
        jdbc.update("DELETE FROM daily_campaign_fraud_analytics");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM impression_log");
        jdbc.update("DELETE FROM deduction_result WHERE owner_id = ?", OWNER);
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
    }

    @Test
    void 노출과_과금_판정이_세_표로_저장되고_무효_노출과_캠페인_경고로_조회된다() throws Exception {
        for (int i = 0; i < 100; i++) {
            impression(CAMPAIGN, HEAVY_MEMBER, "VALID", null, false);
        }
        for (int i = 0; i < 3; i++) {
            impression(CAMPAIGN, OTHER_MEMBER, "VALID", null, true);
        }
        for (int i = 0; i < 70; i++) {
            impression(CAMPAIGN, INVALID_MEMBER, "INVALID", "EXPIRED_OR_UNKNOWN", false);
        }
        for (int i = 0; i < 50; i++) {
            impression(CAMPAIGN, INVALID_MEMBER, "INVALID", "NOT_OWNER", false);
        }
        for (int i = 0; i < 100; i++) {
            impression(UNKNOWN_CAMPAIGN, INVALID_MEMBER, "INVALID", "EXPIRED_OR_UNKNOWN", false);
        }
        deduction("NOT_BILLED_BUDGET", 5);
        deduction("NOT_BILLED_PAUSED", 2);
        deduction("NOT_BILLED_NO_PLAN", 1);

        job.run();

        assertThat(jdbc.queryForList("""
                SELECT campaign_id, member_id, reason_code, invalid_count FROM daily_invalid_analytics
                WHERE business_date = ? ORDER BY campaign_id, reason_code
                """, Date.valueOf(yesterday)))
                .extracting(row -> row.get("campaign_id") + "/" + row.get("member_id") + "/" + row.get("reason_code")
                        + "/" + row.get("invalid_count"))
                .containsExactly(
                        CAMPAIGN + "/" + INVALID_MEMBER + "/EXPIRED_OR_UNKNOWN/70",
                        CAMPAIGN + "/" + INVALID_MEMBER + "/NOT_OWNER/50",
                        UNKNOWN_CAMPAIGN + "/" + INVALID_MEMBER + "/EXPIRED_OR_UNKNOWN/100");
        assertThat(jdbc.queryForList("""
                SELECT not_billed_reason, not_billed_count FROM daily_campaign_not_billed_analytics
                WHERE business_date = ? AND campaign_id = ? ORDER BY not_billed_reason
                """, Date.valueOf(yesterday), CAMPAIGN))
                .extracting(row -> row.get("not_billed_reason") + "/" + row.get("not_billed_count"))
                .containsExactly("NOT_BILLED_BUDGET/5", "NOT_BILLED_NO_PLAN/1", "NOT_BILLED_PAUSED/2",
                        "NOT_BILLED_SOLD_OUT/3");
        Map<String, Object> fraud = jdbc.queryForMap("""
                SELECT * FROM daily_campaign_fraud_analytics WHERE business_date = ? AND campaign_id = ?
                """, Date.valueOf(yesterday), CAMPAIGN);
        assertThat(((Number) fraud.get("valid_count")).longValue()).isEqualTo(103);
        assertThat(((Number) fraud.get("invalid_count")).longValue()).isEqualTo(120);
        assertThat(((Number) fraud.get("distinct_member_count")).longValue()).isEqualTo(2);
        assertThat(((Number) fraud.get("top_members_count")).longValue()).isEqualTo(103);
        assertThat(fraud.get("concentration_suspected")).isEqualTo(true);

        mvc.perform(get("/v1/admin/fraud/invalid-impressions")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaryByReason[0].reasonCode").value("EXPIRED_OR_UNKNOWN"))
                .andExpect(jsonPath("$.data.summaryByReason[0].count").value(170))
                .andExpect(jsonPath("$.data.summaryByReason[1].count").value(50))
                .andExpect(jsonPath("$.data.items[0].campaignId").value(UNKNOWN_CAMPAIGN))
                .andExpect(jsonPath("$.data.items[0].count").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(3));

        mvc.perform(get("/v1/admin/fraud/campaign-warnings")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].campaignId").value(UNKNOWN_CAMPAIGN))
                .andExpect(jsonPath("$.data.items[0].invalidRatio").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].invalidRatioWarning").value(true))
                .andExpect(jsonPath("$.data.items[0].concentrationWarning").value(false))
                .andExpect(jsonPath("$.data.items[1].campaignId").value(CAMPAIGN))
                .andExpect(jsonPath("$.data.items[1].validCount").value(103))
                .andExpect(jsonPath("$.data.items[1].invalidCount").value(120))
                .andExpect(jsonPath("$.data.items[1].invalidRatio").value(1.165049))
                .andExpect(jsonPath("$.data.items[1].invalidRatioWarning").value(true))
                .andExpect(jsonPath("$.data.items[1].concentrationWarning").value(true))
                .andExpect(jsonPath("$.data.items[1].topMembersShare").value(1.0));
    }

    @Test
    void 같은_날짜를_다시_저장하면_사라진_행은_남지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 1, 0);
        invalidRepository.replace(yesterday, List.of(
                new InvalidMetrics.Row(1L, 10L, InvalidReasonCode.NOT_OWNER, 5),
                new InvalidMetrics.Row(2L, 10L, InvalidReasonCode.NOT_OWNER, 6)), now);
        notBilledRepository.replace(yesterday, List.of(
                new NotBilledMetrics.Row(1L, NotBilledReason.NOT_BILLED_BUDGET, 5),
                new NotBilledMetrics.Row(1L, NotBilledReason.NOT_BILLED_PAUSED, 2)), now);
        fraudRepository.replace(yesterday, List.of(
                new FraudMetrics.Row(1L, 100, 5, 3, 60, true),
                new FraudMetrics.Row(2L, 10, 0, 3, 5, false)), now);

        invalidRepository.replace(yesterday, List.of(
                new InvalidMetrics.Row(1L, 10L, InvalidReasonCode.NOT_OWNER, 9)), now);
        notBilledRepository.replace(yesterday, List.of(
                new NotBilledMetrics.Row(1L, NotBilledReason.NOT_BILLED_BUDGET, 7)), now);
        fraudRepository.replace(yesterday, List.of(new FraudMetrics.Row(1L, 100, 5, 3, 40, false)), now);

        assertThat(jdbc.queryForObject("SELECT SUM(invalid_count) FROM daily_invalid_analytics WHERE business_date = ?",
                Long.class, Date.valueOf(yesterday))).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_invalid_analytics WHERE business_date = ?",
                Long.class, Date.valueOf(yesterday))).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT not_billed_reason, not_billed_count FROM daily_campaign_not_billed_analytics
                WHERE business_date = ?
                """, Date.valueOf(yesterday)))
                .extracting(row -> row.get("not_billed_reason") + "/" + row.get("not_billed_count"))
                .containsExactly("NOT_BILLED_BUDGET/7");
        assertThat(jdbc.queryForList("""
                SELECT campaign_id, top_members_count, concentration_suspected FROM daily_campaign_fraud_analytics
                WHERE business_date = ?
                """, Date.valueOf(yesterday)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(((Number) row.get("top_members_count")).longValue()).isEqualTo(40);
                    assertThat(row.get("concentration_suspected")).isEqualTo(false);
                });
    }

    @Test
    void 다른_날짜의_행은_교체하지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 17, 1, 0);
        invalidRepository.replace(twoDaysAgo, List.of(new InvalidMetrics.Row(1L, 10L, InvalidReasonCode.NOT_OWNER, 5)), now);

        invalidRepository.replace(yesterday, List.of(), now);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_invalid_analytics WHERE business_date = ?",
                Long.class, Date.valueOf(twoDaysAgo))).isEqualTo(1);
    }

    private void sourceDone(LocalDate date) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, created_at, updated_at)
                VALUES ('BILLING_DAILY_RELEASE', ?, 'SUCCESS', ?, NULL, 'other-server', ?, ?)
                """, Date.valueOf(date), now, now, now);
    }

    private void impression(long campaignId, long memberId, String validity, String reason, boolean soldOut) {
        boolean valid = validity.equals("VALID");
        Timestamp received = Timestamp.valueOf(yesterday.atTime(10, 30));
        jdbc.update("""
                INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type,
                    slot_position, displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 37.498, 127.028, ?, ?, ?, ?, ?)
                """, newServeId(), memberId, Date.valueOf(yesterday), campaignId, valid ? 55L : null,
                valid ? "ALLOCATION" : null, valid ? 1 : null, received, validity, reason, soldOut, received, received);
    }

    private void deduction(String status, int count) {
        Timestamp at = Timestamp.valueOf(yesterday.atTime(11, 0));
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO deduction_result
                        (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 100, ?, ?)
                    """, newServeId(), CAMPAIGN, OWNER, Date.valueOf(yesterday), status, at, at);
        }
    }

    private static byte[] newServeId() {
        UUID id = UUID.randomUUID();
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
