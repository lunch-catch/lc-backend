package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.analytics.batch.job.DailyAggregationJob;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.global.config.ClockConfig;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 캠페인 리포트의 소진액이 정산 원장에서 리포트 행을 거쳐 A-14 까지 같은 값으로 가는지 실제 MySQL 로 끝까지 확인한다(#625).
 * 점주에게 보이는 소진 포인트라 돈이 걸린 경로다. 입구는 시각(배치)과 HTTP(GET /v1/admin/campaign-reports) 둘이다.
 *
 * 리포트 행의 점주와 가게가 캠페인 연결 정보에서 오는지, 다시 집계해도 같은 ID 와 처음 확정 시각을 유지하고 자식 행이
 * 두 번 쌓이지 않는지도 함께 본다. 직접 쓴 upsert 와 자식 교체 SQL 을 한 번은 실제로 돌려 봐야 해서다.
 */
class CampaignReportIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long OWNER = 973_000L;
    private static final long STORE = 973_200L;
    private static final long CAMPAIGN = 973_100L;
    private static final long UNIT_PRICE = 2L;

    private final LocalDate today = LocalDate.now(ClockConfig.ZONE);
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate twoDaysAgo = today.minusDays(2);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private DailyAggregationJob job;

    @BeforeEach
    void setUp() {
        removeSeeds();
        campaign();
        charge();
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
    }

    /*
     * 심은 행을 테스트가 끝날 때도 지운다. 같은 DB 를 쓰는 다른 집계 테스트가 이 테스트의 미과금 판정과 캠페인을
     * 원천으로 읽어 그날 쓰는 행 수가 달라지기 때문이다.
     */
    @AfterEach
    void tearDown() {
        removeSeeds();
    }

    private void removeSeeds() {
        jdbc.update("DELETE FROM batch_execution_log");
        jdbc.update("DELETE FROM aggregation_pending_date");
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM deduction_result WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM payment WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM event_log WHERE campaign_id = ?", CAMPAIGN);
        jdbc.update("DELETE FROM impression_log WHERE campaign_id = ?", CAMPAIGN);
        for (String child : new String[] {"campaign_daily_impression_reason_report", "campaign_hourly_report",
                "campaign_daily_slot_report", "campaign_daily_report_history"}) {
            jdbc.update("DELETE FROM " + child + " WHERE campaign_daily_report_id IN "
                    + "(SELECT campaign_daily_report_id FROM campaign_daily_report WHERE campaign_id = ?)", CAMPAIGN);
        }
        jdbc.update("DELETE FROM campaign_daily_report WHERE campaign_id = ?", CAMPAIGN);
        jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", CAMPAIGN);
    }

    @Test
    void 정산_원장의_차감이_리포트_행과_A_14_의_소진액으로_같게_간다() throws Exception {
        String at = yesterday.atTime(12, 0).toString();
        billedDeduct(at);
        billedDeduct(at);
        wish(yesterday.atTime(12, 5));

        job.run();

        Map<String, Object> row = report(yesterday);
        assertThat(((Number) row.get("owner_id")).longValue()).isEqualTo(OWNER);
        assertThat(((Number) row.get("store_id")).longValue()).isEqualTo(STORE);
        assertThat(((Number) row.get("charged_amount")).longValue()).isEqualTo(2 * UNIT_PRICE);
        assertThat(((Number) row.get("spent_amount")).longValue()).isEqualTo(2 * UNIT_PRICE);
        assertThat(((Number) row.get("billable_impression_count")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("valid_impression_count")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("wish_count")).longValue()).isEqualTo(1);
        assertThat(row.get("report_status")).isEqualTo("FINALIZED");
        assertThat(row.get("finalized_at")).isNotNull();

        mvc.perform(get("/v1/admin/campaign-reports")
                        .param("campaignIds", CAMPAIGN + ",973199")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].campaignId").value(CAMPAIGN))
                .andExpect(jsonPath("$.data.items[0].spentAmount").value(2 * UNIT_PRICE))
                .andExpect(jsonPath("$.data.items[0].validImpressionCount").value(2))
                .andExpect(jsonPath("$.data.items[0].todayStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.items[1].campaignId").value(973_199))
                .andExpect(jsonPath("$.data.items[1].spentAmount").value(0));
    }

    @Test
    void 광고_서빙이_새로_붙인_슬롯_이름표도_분석_코드_수정_없이_이름표별로_남긴다() {
        String at = yesterday.atTime(12, 0).toString();
        billedDeduct(at);
        billedDeduct(at);
        impression("ALLOCATION", yesterday.atTime(12, 0));
        impression("NEW_STORE", yesterday.atTime(12, 1));

        job.run();

        Object reportId = report(yesterday).get("campaign_daily_report_id");
        Map<String, Long> slots = new HashMap<>();
        jdbc.query("SELECT slot_type, impression_count FROM campaign_daily_slot_report WHERE campaign_daily_report_id = ?",
                rs -> {
                    slots.put(rs.getString("slot_type"), rs.getLong("impression_count"));
                }, reportId);
        assertThat(slots).containsExactlyInAnyOrderEntriesOf(Map.of("ALLOCATION", 1L, "NEW_STORE", 1L));
    }

    @Test
    void 같은_날짜를_다시_집계해도_리포트_ID_와_처음_확정_시각을_유지하고_자식_행이_쌓이지_않는다() {
        billedDeduct(yesterday.atTime(12, 0).toString());
        deduction("NOT_BILLED_BUDGET", yesterday.atTime(12, 1).toString());
        job.run();
        Map<String, Object> first = report(yesterday);
        long firstChildren = children(first);
        assertThat(firstChildren).isEqualTo(1);

        jdbc.update("DELETE FROM batch_execution_log");
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        job.run();

        Map<String, Object> second = report(yesterday);
        assertThat(second.get("campaign_daily_report_id")).isEqualTo(first.get("campaign_daily_report_id"));
        assertThat(second.get("finalized_at")).isEqualTo(first.get("finalized_at"));
        assertThat(children(second)).isEqualTo(firstChildren);
    }

    /*
     * 확정된 리포트가 늦게 도착한 차감으로 바뀌면 변경 전후를 이력으로 남긴다(#632).
     * 점주에게 보이는 소진액이 덮이는 경로라 직접 쓴 JSON_OBJECT 와 차수 조회 SQL 을 실제로 돌린다.
     * 값이 그대로인 재집계는 차수를 올리지 않는다.
     */
    @Test
    void 확정된_리포트가_늦게_도착한_차감으로_바뀌면_변경_전후를_이력으로_남긴다() {
        billedDeduct(yesterday.atTime(12, 0).toString());
        job.run();
        Map<String, Object> first = report(yesterday);

        billedDeduct(yesterday.atTime(12, 30).toString());
        rerun();
        rerun();

        Map<String, Object> history = jdbc.queryForMap("""
                SELECT revision_no, change_reason,
                       JSON_EXTRACT(before_data, '$.chargedAmount') AS before_charged,
                       JSON_EXTRACT(after_data, '$.chargedAmount') AS after_charged,
                       JSON_EXTRACT(after_data, '$.billableImpressionCount') AS after_billable,
                       JSON_LENGTH(after_data) AS keys_count
                  FROM campaign_daily_report_history WHERE campaign_daily_report_id = ?
                """, first.get("campaign_daily_report_id"));
        assertThat(((Number) history.get("revision_no")).intValue()).isEqualTo(1);
        assertThat(history.get("change_reason")).isEqualTo("LATE_EVENT");
        assertThat(history.get("before_charged").toString()).isEqualTo(String.valueOf(UNIT_PRICE));
        assertThat(history.get("after_charged").toString()).isEqualTo(String.valueOf(2 * UNIT_PRICE));
        assertThat(history.get("after_billable").toString()).isEqualTo("2");
        assertThat(((Number) history.get("keys_count")).intValue()).isEqualTo(13);
    }

    // 성공한 집계 기록을 지워 같은 날짜를 다시 집계하게 한다
    private void rerun() {
        jdbc.update("DELETE FROM batch_execution_log");
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        job.run();
    }

    // 집계 중(AGGREGATING) 행은 확정 전 값이라 A-14 의 소진액과 유효 노출에 넣지 않는다(#895)
    @Test
    void A_14_는_집계_중인_날의_리포트를_합하지_않는다() throws Exception {
        billedDeduct(yesterday.atTime(12, 0).toString());
        job.run();
        jdbc.update("""
                INSERT INTO campaign_daily_report
                    (campaign_id, owner_id, store_id, business_date, daily_budget, charged_amount, spent_amount,
                     valid_impression_count, billable_impression_count, report_status, finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 10000, 500, 500, 250, 250, 'AGGREGATING', NULL, NOW(6), NOW(6))
                """, CAMPAIGN, OWNER, STORE, Date.valueOf(today));

        mvc.perform(get("/v1/admin/campaign-reports")
                        .param("campaignIds", String.valueOf(CAMPAIGN))
                        .param("from", yesterday.toString())
                        .param("to", today.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].spentAmount").value(UNIT_PRICE))
                .andExpect(jsonPath("$.data.items[0].validImpressionCount").value(1));
    }

    private Map<String, Object> report(LocalDate date) {
        return jdbc.queryForMap("SELECT * FROM campaign_daily_report WHERE campaign_id = ? AND business_date = ?",
                CAMPAIGN, Date.valueOf(date));
    }

    private long children(Map<String, Object> report) {
        Object id = report.get("campaign_daily_report_id");
        long count = 0;
        for (String child : new String[] {"campaign_daily_impression_reason_report", "campaign_hourly_report",
                "campaign_daily_slot_report"}) {
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + child + " WHERE campaign_daily_report_id = ?",
                    Long.class, id);
            count += rows == null ? 0 : rows;
        }
        return count;
    }

    private void campaign() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO campaign
                    (campaign_id, owner_id, store_id, status, status_changed_at, discount_target_type,
                     discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time,
                     target_radius, target_gender, target_age_groups, daily_budget,
                     start_date, end_date, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, 'ALL', 'AMOUNT', 2000, 50, '11:00', '14:00',
                        1000, 'ALL', 'AGE_20S', 10000, ?, ?, ?, ?)
                """, CAMPAIGN, OWNER, STORE, now, Date.valueOf(twoDaysAgo), Date.valueOf(today.plusDays(30)),
                now, now);
    }

    /*
     * 차감만 심으면 정산 지표의 미소진 잔액이 음수가 되어 daily_settlement_analytics 의 CHECK 에 걸리고 그날 집계 전체가 실패한다.
     * 그래서 차감보다 먼저 충전을 심는다.
     */
    private void charge() {
        String orderId = "report-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (owner_id, order_id, pg_tid, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, 10000, 'REQUESTED', NOW(6), NOW(6))
                """, OWNER, orderId, "pg-" + orderId);
        Long paymentId = jdbc.queryForObject("SELECT payment_id FROM payment WHERE order_id = ?", Long.class, orderId);
        String at = twoDaysAgo.atTime(9, 0).toString();
        jdbc.update("""
                INSERT INTO point_ledger (owner_id, campaign_id, payment_id, refund_request_id, business_date, entry_type,
                                          source, amount, reason, idempotency_key, created_at, updated_at)
                VALUES (?, NULL, ?, NULL, NULL, 'CHARGE', 'RECONCILIATION', 10000, NULL, NULL, ?, ?)
                """, OWNER, paymentId, at, at);
    }

    // 슬롯 이름표만 바꿔 넣는 광고 서빙의 유효 노출 한 건이다
    private void impression(String slotType, LocalDateTime receivedAt) {
        Timestamp received = Timestamp.valueOf(receivedAt);
        jdbc.update("""
                INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type,
                    slot_position, displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out,
                    created_at, updated_at)
                VALUES (UUID_TO_BIN(UUID()), 3001, ?, ?, ?, ?, 1, ?, 37.498, 127.028, 'VALID', NULL, FALSE, ?, ?)
                """, Date.valueOf(receivedAt.toLocalDate()), CAMPAIGN, STORE, slotType, received, received, received);
    }

    private void billedDeduct(String at) {
        byte[] serveId = deduction("BILLED", at);
        jdbc.update("""
                INSERT INTO point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                VALUES (?, ?, ?, 'DEDUCT', 'REALTIME', ?, ?, ?)
                """, OWNER, CAMPAIGN, serveId, -UNIT_PRICE, at, at);
    }

    private byte[] deduction(String billingStatus, String at) {
        UUID serve = UUID.randomUUID();
        byte[] serveId = ByteBuffer.allocate(16)
                .putLong(serve.getMostSignificantBits())
                .putLong(serve.getLeastSignificantBits())
                .array();
        jdbc.update("""
                INSERT INTO deduction_result
                    (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, serveId, CAMPAIGN, OWNER, Date.valueOf(yesterday), billingStatus, UNIT_PRICE, at, at);
        return serveId;
    }

    private void wish(LocalDateTime occurredAt) {
        Timestamp at = Timestamp.valueOf(occurredAt);
        jdbc.update("""
                INSERT INTO event_log (event_id, event_type, member_id, campaign_id, store_id, wish_id,
                                       occurred_at, created_at, updated_at)
                VALUES (?, 'wish', 973300, ?, ?, 1, ?, ?, ?)
                """, UUID.randomUUID().toString(), CAMPAIGN, STORE, at, at, at);
    }

    private void sourceDone(LocalDate date) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, created_at, updated_at)
                VALUES ('BILLING_DAILY_RELEASE', ?, 'SUCCESS', ?, NULL, 'other-server', ?, ?)
                """, Date.valueOf(date), now, now, now);
    }
}
