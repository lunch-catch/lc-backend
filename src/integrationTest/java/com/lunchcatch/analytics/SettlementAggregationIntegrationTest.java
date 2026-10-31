package com.lunchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.analytics.batch.job.DailyAggregationJob;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.config.ClockConfig;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 돈이 걸린 경로 하나를 실제 MySQL 로 끝까지 확인한다. 정산 원장 행을 심고 01:00 작업의 run() 으로
 * 집계한 뒤 daily_settlement_analytics 의 저장 부호와 A-07 응답을 본다. 입구는 시각(배치)과
 * HTTP(GET /v1/admin/sales) 둘이다.
 *
 * 미소진 잔액이 원장 전체의 누적 합이라 심기 전에 원장을 비운다. 다른 테스트가 남긴 행이 있으면
 * 잔액이 달라지거나 음수가 되어 CHECK 에 걸린다.
 */
class SettlementAggregationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long OWNER = 970_000L;
    private static final long CAMPAIGN = 970_100L;

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
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log");
        jdbc.update("DELETE FROM aggregation_pending_date");
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM deduction_result WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM payment WHERE owner_id = ?", OWNER);
        jdbc.update("DELETE FROM refund_request WHERE owner_id = ?", OWNER);
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
    }

    @Test
    void 정산_원장이_하루_지표_한_행으로_저장되고_매출_현황으로_조회된다() throws Exception {
        String noon = yesterday + " 12:00:00";
        ledger("CHARGE", null, 10_000, null, noon);
        ledger("RESERVE", CAMPAIGN, -5_000, yesterday.toString(), noon);
        for (int i = 0; i < 3; i++) {
            billedDeduct(100, noon);
        }
        ledger("RELEASE", CAMPAIGN, 4_700, yesterday.toString(), noon);
        ledger("REFUND", null, -400, null, noon);
        ledger("ADJUST", CAMPAIGN, 50, null, noon);
        ledger("ADJUST", CAMPAIGN, -20, null, noon);

        job.run();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM daily_settlement_analytics WHERE business_date = ?", java.sql.Date.valueOf(yesterday));
        assertThat(((Number) row.get("charge_amount")).longValue()).isEqualTo(10_000);
        assertThat(((Number) row.get("deduct_amount")).longValue()).isEqualTo(300);
        assertThat(((Number) row.get("reserved_amount")).longValue()).isEqualTo(5_000);
        assertThat(((Number) row.get("released_amount")).longValue()).isEqualTo(4_700);
        assertThat(((Number) row.get("refund_amount")).longValue()).isEqualTo(400);
        assertThat(((Number) row.get("adjust_amount")).longValue()).isEqualTo(30);
        assertThat(((Number) row.get("positive_adjust_amount")).longValue()).isEqualTo(50);
        assertThat(((Number) row.get("unspent_balance")).longValue()).isEqualTo(10_000 - 400 + 30 - 300);

        mvc.perform(get("/v1/admin/sales")
                        .param("period", "DAILY")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FINAL"))
                .andExpect(jsonPath("$.data.items[0].chargeAmount").value(10_000))
                .andExpect(jsonPath("$.data.items[0].spentAmount").value(250))
                .andExpect(jsonPath("$.data.items[0].refundAmount").value(400))
                .andExpect(jsonPath("$.data.items[0].adjustAmount").value(30))
                .andExpect(jsonPath("$.data.items[0].unspentBalance").value(9_330));
    }

    @Test
    void 정정이_차감보다_커서_소진액이_음수여도_0으로_자르지_않고_저장하고_내려준다() throws Exception {
        String noon = yesterday + " 12:00:00";
        ledger("CHARGE", null, 5_000, null, noon);
        ledger("RESERVE", CAMPAIGN, -1_000, yesterday.toString(), noon);
        billedDeduct(100, noon);
        ledger("RELEASE", CAMPAIGN, 900, yesterday.toString(), noon);
        ledger("ADJUST", CAMPAIGN, 350, null, noon);

        job.run();

        mvc.perform(get("/v1/admin/sales")
                        .param("period", "DAILY")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].spentAmount").value(-250));
        assertThat(jdbc.queryForObject(
                "SELECT deduct_amount - positive_adjust_amount FROM daily_settlement_analytics WHERE business_date = ?",
                Long.class, java.sql.Date.valueOf(yesterday))).isEqualTo(-250L);
    }

    private void sourceDone(LocalDate date) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, created_at, updated_at)
                VALUES ('BILLING_DAILY_RELEASE', ?, 'SUCCESS', ?, NULL, 'other-server', ?, ?)
                """, java.sql.Date.valueOf(date), now, now, now);
    }

    private void ledger(String type, Long campaign, long amount, String businessDate, String at) {
        boolean adjust = type.equals("ADJUST");
        Long paymentId = type.equals("CHARGE") ? payment() : null;
        Long refundRequestId = type.equals("REFUND") ? refundRequest() : null;
        jdbc.update("""
                INSERT INTO point_ledger (owner_id, campaign_id, payment_id, refund_request_id, business_date, entry_type,
                                          source, amount, reason, idempotency_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, OWNER, campaign, paymentId, refundRequestId, businessDate, type,
                adjust || refundRequestId != null ? "MANUAL" : "RECONCILIATION", amount,
                adjust ? "정정" : null, adjust ? UUID.randomUUID().toString() : null, at, at);
    }

    private long payment() {
        String orderId = "settle-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment (owner_id, order_id, pg_tid, amount, status, created_at, updated_at)
                VALUES (?, ?, ?, 10000, 'REQUESTED', NOW(6), NOW(6))
                """, OWNER, orderId, "pg-" + orderId);
        return jdbc.queryForObject("SELECT payment_id FROM payment WHERE order_id = ?", Long.class, orderId);
    }

    private long refundRequest() {
        jdbc.update("""
                INSERT INTO refund_request (owner_id, amount, fee, status, created_at, updated_at)
                VALUES (?, 400, 0, 'REQUESTED', NOW(6), NOW(6))
                """, OWNER);
        return jdbc.queryForObject("SELECT MAX(refund_request_id) FROM refund_request WHERE owner_id = ?",
                Long.class, OWNER);
    }

    private void billedDeduct(long unitPrice, String at) {
        UUID serve = UUID.randomUUID();
        byte[] serveId = ByteBuffer.allocate(16)
                .putLong(serve.getMostSignificantBits())
                .putLong(serve.getLeastSignificantBits())
                .array();
        jdbc.update("""
                INSERT INTO deduction_result
                    (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'BILLED', ?, ?, ?)
                """, serveId, CAMPAIGN, OWNER, java.sql.Date.valueOf(yesterday), unitPrice, at, at);
        jdbc.update("""
                INSERT INTO point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                VALUES (?, ?, ?, 'DEDUCT', 'REALTIME', ?, ?, ?)
                """, OWNER, CAMPAIGN, serveId, -unitPrice, at, at);
    }
}
