package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.global.config.DomainFlywayMigrator.BaseSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/*
 * 시연 층(db/base 의 R__demo.sql)이 demo.md 의 시연 계정 상태를 만드는지 본다 (#741).
 * 기준일을 바꿔 다시 돌리면 시연 데이터가 새 기준일로 다시 만들어지는지도 본다.
 */
class DemoLayerIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";
    private static final long OWNER = 20_000_011L;
    private static final long OWNER_NEW = 20_000_012L;
    private static final long OWNER_SOLDOUT = 20_000_014L;
    private static final long OWNER_NOPOINTS = 20_000_015L;

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 빈_데이터베이스를_만든다() throws SQLException {
        String database = "demo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database)
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    private void migrate(String baseDate) {
        Map<String, String> placeholders = Map.of("baseDate", baseDate, "superadminPasswordHash", HASH,
                "demoAdminPasswordHash", HASH, "demoOwnerPasswordHash", HASH, "scenarioPasswordHash", HASH,
                "baseCdnBaseUrl", "https://cdn.example.com");
        new DomainFlywayMigrator(dataSource, new BaseSettings(true, placeholders)).afterPropertiesSet();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long balance(long ownerId) {
        return count("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE owner_id = ? AND entry_type <> 'DEDUCT'", ownerId);
    }

    // OwnerPointBalanceService 의 예약 포인트와 같은 계산이다 (예약 - 해제 - DEDUCT)
    private long reserved(long ownerId) {
        return count("SELECT COALESCE(SUM(CASE entry_type WHEN 'DEDUCT' THEN amount ELSE -amount END), 0) FROM point_ledger "
                + "WHERE owner_id = ? AND entry_type IN ('RESERVE', 'RELEASE', 'DEDUCT')", ownerId);
    }

    @Test
    void 시연_계정과_장면별_상태를_만든다() {
        migrate("2026-10-28");

        assertThat(count("SELECT COUNT(*) FROM admin WHERE login_id IN ('superadmin', 'demo-admin') AND status = 'ACTIVE'"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM owner WHERE email LIKE 'demo-owner%@lunchcatch.com' AND status = 'ACTIVE'"))
                .isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM store WHERE owner_id BETWEEN 20000011 AND 20000015 AND finalized = 1"))
                .isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM store_menu WHERE store_id = ?", OWNER)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM store_image WHERE store_id = ?", OWNER)).isEqualTo(4);
        // 시연 캠페인 7개 모두 게시된 템플릿으로 만든 포스터와 검수 통과 결과가 있다 (R__templates_posters, #858)
        assertThat(count("SELECT COUNT(*) FROM poster p JOIN poster_moderation_result r ON r.poster_id = p.poster_id "
                + "JOIN template t ON t.template_id = p.template_id WHERE p.campaign_id BETWEEN 20000101 AND 20000999 "
                + "AND r.result = 'PASS' AND t.status = 'PUBLISHED'")).isEqualTo(7);

        // demo-owner: 진행 중 1, 끝난 3, 오늘 예약이 있고 잔액이 남는다
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = ? AND status = 'ACTIVE'", OWNER)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = ? AND status = 'ENDED'", OWNER)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE owner_id = ? AND entry_type = 'RESERVE' AND business_date = '2026-10-28'",
                OWNER)).isEqualTo(1);
        assertThat(balance(OWNER)).isPositive();
        // 예약 포인트는 오늘 예약 20,000 뿐이다. 지난 날은 해제와 DEDUCT 로 모두 닫혔다
        assertThat(reserved(OWNER)).isEqualTo(20_000L);
        assertThat(count("SELECT COUNT(*) FROM campaign_daily_report WHERE campaign_id = 20000101 AND report_status = 'FINALIZED'"))
                .isEqualTo(14);
        assertThat(count("SELECT COUNT(*) FROM coupon_inventory i JOIN coupon_event e ON e.coupon_event_id = i.coupon_event_id "
                + "WHERE e.campaign_id = 20000101 AND e.business_date = '2026-10-28' AND i.status = 'AVAILABLE'")).isEqualTo(50);

        // demo-owner-new: 가게와 잔액 100,000, 캠페인 없음
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = ?", OWNER_NEW)).isZero();
        assertThat(balance(OWNER_NEW)).isEqualTo(100_000L);

        // demo-owner-scheduled: 시작일이 기준일인 SCHEDULED
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id = 20000301 AND status = 'SCHEDULED' AND start_date = '2026-10-28'"))
                .isEqualTo(1);

        // demo-owner-soldout: 수량 3 중 오늘 2장 발급
        assertThat(count("SELECT COUNT(*) FROM coupon_inventory i JOIN coupon_event e ON e.coupon_event_id = i.coupon_event_id "
                + "WHERE e.campaign_id = 20000401 AND e.business_date = '2026-10-28' AND i.status = 'ISSUED'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = ? AND issue_quantity = 3 AND status = 'ACTIVE'",
                OWNER_SOLDOUT)).isEqualTo(1);

        // demo-owner-nopoints: PAUSED(NO_POINTS), 잔액 0, 오늘 예산 0
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = ? AND status = 'PAUSED' AND paused_reason = 'NO_POINTS'",
                OWNER_NOPOINTS)).isEqualTo(1);
        assertThat(balance(OWNER_NOPOINTS)).isZero();
        assertThat(reserved(OWNER_NOPOINTS)).isZero();
        assertThat(count("SELECT COUNT(*) FROM budget_daily_plan WHERE campaign_id = 20000501 AND business_date = '2026-10-28' "
                + "AND daily_budget = 0 AND paused = 1")).isEqualTo(1);

        // 오늘 피드 후보는 진행 중 시연 캠페인 둘이다
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-28' AND campaign_id BETWEEN 20000101 AND 20000599"))
                .isEqualTo(2);
    }

    @Test
    void 기준일이_바뀌면_시연_데이터를_새_기준일로_다시_만든다() {
        migrate("2026-10-28");
        long reports = count("SELECT COUNT(*) FROM campaign_daily_report WHERE campaign_id BETWEEN 20000101 AND 20000599");
        // 리허설에서 생긴 행 흉내: 시연 점주의 원장이 하나 늘었다
        jdbc.update("INSERT INTO point_ledger (owner_id, entry_type, source, amount, reason, idempotency_key, created_at, updated_at) "
                + "VALUES (?, 'ADJUST', 'MANUAL', 1000, '리허설 보상', 'rehearsal-1', NOW(6), NOW(6))", OWNER_NEW);

        migrate("2026-10-29");

        assertThat(count("SELECT COUNT(*) FROM campaign_daily_report WHERE campaign_id BETWEEN 20000101 AND 20000599"))
                .isEqualTo(reports);
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-29' AND campaign_id BETWEEN 20000101 AND 20000599"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-28' AND campaign_id BETWEEN 20000101 AND 20000599"))
                .isZero();
        assertThat(balance(OWNER_NEW)).isEqualTo(100_000L);
        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id BETWEEN 20000011 AND 20000015")).isEqualTo(5);
    }
}
