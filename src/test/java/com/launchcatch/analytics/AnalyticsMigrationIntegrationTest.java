package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class AnalyticsMigrationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 15, 12, 0);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void analytics_V1_다음에_V2가_적용되고_새_테이블과_컬럼이_생긴다() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_history_analytics WHERE success = 1 ORDER BY installed_rank",
                String.class);

        assertThat(versions).endsWith("1", "2");
        assertThat(columnsOf("event_log"))
                .contains("coupon_event_id", "member_coupon_id")
                .doesNotContain("issue_id");
        assertThat(columnsOf("daily_settlement_analytics")).contains("positive_adjust_amount");
        assertThat(columnsOf("campaign_daily_report")).contains("owner_id", "store_id");
        assertThat(existingTables()).contains(
                "daily_campaign_fraud_analytics",
                "daily_member_usage",
                "aggregation_pending_date");
    }

    @Test
    void 이벤트_유형별_식별자_조합을_허용한다() {
        assertThatCode(() -> insertEvent("map_view", 1L, null, null, null, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("issue_open", null, 10L, 20L, null, 30L, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("issue_open", null, null, null, null, 30L, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("issue", 1L, 10L, 20L, null, 30L, 40L))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("redeem", 1L, 10L, 20L, null, null, 40L))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertEvent("expire", 1L, 10L, 20L, null, 30L, 40L))
                .doesNotThrowAnyException();
    }

    @Test
    void 이벤트_유형별_잘못된_식별자_조합을_거부한다() {
        assertRejected(() -> insertEvent("map_view", 1L, 10L, 20L, null, null, null));
        assertRejected(() -> insertEvent("poster_open", 1L, null, null, null, null, null));
        assertRejected(() -> insertEvent("issue_open", 1L, 10L, 20L, null, 30L, null));
        assertRejected(() -> insertEvent("issue", 1L, 10L, 20L, null, 30L, null));
        assertRejected(() -> insertEvent("redeem", 1L, 10L, 20L, null, null, null));
    }

    @Test
    void 점주와_세그먼트의_소진액은_음수를_허용한다() {
        assertThatCode(() -> jdbc.update("""
                INSERT INTO daily_owner_spend
                    (business_date, owner_id, spent_amount, created_at, updated_at)
                VALUES (?, 1, -100, ?, ?)
                """, BUSINESS_DATE, OCCURRED_AT, OCCURRED_AT)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update("""
                INSERT INTO daily_segment_analytics
                    (business_date, category_code, region, spent_amount, created_at, updated_at)
                VALUES (?, 'KOREAN', '서울 강남구', -100, ?, ?)
                """, BUSINESS_DATE, OCCURRED_AT, OCCURRED_AT)).doesNotThrowAnyException();
    }

    @Test
    void 양수_조정액은_음수를_거부한다() {
        assertRejected(() -> jdbc.update("""
                INSERT INTO daily_settlement_analytics
                    (business_date, positive_adjust_amount, created_at, updated_at)
                VALUES (?, -1, ?, ?)
                """, BUSINESS_DATE, OCCURRED_AT, OCCURRED_AT));
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);
    }

    private List<String> existingTables() {
        return jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = DATABASE()
                """, String.class);
    }

    private void insertEvent(
            String eventType,
            Long memberId,
            Long campaignId,
            Long storeId,
            Long wishId,
            Long couponEventId,
            Long memberCouponId) {
        jdbc.update("""
                INSERT INTO event_log
                    (event_id, event_type, member_id, campaign_id, store_id, wish_id,
                     coupon_event_id, member_coupon_id, occurred_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), eventType, memberId, campaignId, storeId, wishId,
                couponEventId, memberCouponId, OCCURRED_AT, OCCURRED_AT, OCCURRED_AT);
    }

    private void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class);
    }
}
