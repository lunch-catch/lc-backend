package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
                .contains("issue_id", "coupon_event_id", "member_coupon_id");
        assertThat(columnsOf("daily_settlement_analytics")).contains("positive_adjust_amount");
        assertThat(columnsOf("campaign_daily_report")).contains("owner_id", "store_id");
        assertThat(existingTables()).contains(
                "daily_campaign_fraud_analytics",
                "daily_member_usage",
                "aggregation_pending_date");
    }

    @Test
    void 기존_V1_이벤트와_캠페인_리포트를_보존하며_V2로_올라간다() throws SQLException {
        String database = "analytics_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database);
        DataSource legacyDataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        JdbcTemplate legacyJdbc = new JdbcTemplate(legacyDataSource);

        analyticsFlyway(legacyDataSource)
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();
        legacyJdbc.update("""
                INSERT INTO event_log
                    (event_id, event_type, member_id, campaign_id, store_id, issue_id,
                     occurred_at, created_at, updated_at)
                VALUES
                    ('legacy-issue-open', 'issue_open', 1, 10, 20, 30, ?, ?, ?),
                    ('legacy-issue', 'issue', 1, 10, 20, 40, ?, ?, ?)
                """, OCCURRED_AT, OCCURRED_AT, OCCURRED_AT,
                OCCURRED_AT, OCCURRED_AT, OCCURRED_AT);
        legacyJdbc.update("""
                INSERT INTO campaign_daily_report
                    (campaign_id, business_date, created_at, updated_at)
                VALUES (10, ?, ?, ?)
                """, BUSINESS_DATE, OCCURRED_AT, OCCURRED_AT);

        analyticsFlyway(legacyDataSource).load().migrate();

        assertThat(columnsOf(legacyJdbc, "event_log"))
                .contains("issue_id", "coupon_event_id", "member_coupon_id");
        assertThat(legacyJdbc.queryForObject(
                "SELECT COUNT(*) FROM event_log WHERE issue_id IN (30, 40)", Long.class)).isEqualTo(2);
        assertThat(legacyJdbc.queryForObject(
                "SELECT member_id FROM event_log WHERE event_id = 'legacy-issue-open'", Long.class)).isEqualTo(1);
        assertThat(legacyJdbc.queryForObject(
                "SELECT owner_id FROM campaign_daily_report WHERE campaign_id = 10", Long.class)).isNull();
        assertThat(legacyJdbc.queryForObject(
                "SELECT store_id FROM campaign_daily_report WHERE campaign_id = 10", Long.class)).isNull();
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
        return columnsOf(jdbc, table);
    }

    private List<String> columnsOf(JdbcTemplate source, String table) {
        return source.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);
    }

    private FluentConfiguration analyticsFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/analytics")
                .table("flyway_history_analytics");
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
