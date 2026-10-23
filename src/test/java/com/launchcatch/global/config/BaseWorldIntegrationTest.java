package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.global.config.DomainFlywayMigrator.BaseSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/*
 * 기본 세계(db/base 의 V2 ~)가 빈 데이터베이스에 끝까지 들어가는지와 걸린 시간을 본다 (#741).
 * 표마다 행 수를 로그로 남겨 규모를 확인한다. 묶음별 확인은 각 파일의 검사 쿼리로 한다.
 */
class BaseWorldIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";


    private static final String[][] CONSISTENCY = {
            {"캠페인의 가게가 있다", "SELECT COUNT(*) FROM campaign c LEFT JOIN store s ON s.store_id = c.store_id WHERE c.campaign_id >= 10000000 AND s.store_id IS NULL"},
            {"캠페인의 점주가 가게 점주와 같다", "SELECT COUNT(*) FROM campaign c JOIN store s ON s.store_id = c.store_id WHERE c.campaign_id >= 10000000 AND c.owner_id <> s.owner_id"},
            {"캠페인 점주는 ACTIVE 다", "SELECT COUNT(*) FROM campaign c JOIN owner o ON o.owner_id = c.owner_id WHERE c.campaign_id >= 10000000 AND o.status <> 'ACTIVE'"},
            {"MENU 캠페인의 메뉴가 그 가게 메뉴다", "SELECT COUNT(*) FROM campaign c LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id AND m.store_id = c.store_id WHERE c.discount_target_type = 'MENU' AND m.store_menu_id IS NULL"},
            {"쿠폰 이벤트의 가게 이름이 가게와 같다", "SELECT COUNT(*) FROM coupon_event e JOIN store s ON s.store_id = e.store_id WHERE e.store_name <> s.name"},
            {"쿠폰 이벤트의 캠페인과 가게가 맞다", "SELECT COUNT(*) FROM coupon_event e LEFT JOIN campaign c ON c.campaign_id = e.campaign_id AND c.store_id = e.store_id WHERE c.campaign_id IS NULL"},
            {"쿠폰 이벤트 할인이 캠페인과 같다", "SELECT COUNT(*) FROM coupon_event e JOIN campaign c ON c.campaign_id = e.campaign_id WHERE e.discount_target_type <> c.discount_target_type OR e.discount_type <> c.discount_type OR e.discount_value <> c.discount_value OR e.issue_quantity <> c.issue_quantity"},
            {"쿠폰 이벤트 메뉴 이름과 가격이 메뉴와 같다", "SELECT COUNT(*) FROM coupon_event e JOIN store_menu m ON m.store_menu_id = e.target_menu_id WHERE e.target_menu_name <> m.name OR e.target_menu_price <> m.price"},
            {"후보의 가게와 좌표가 가게와 같다", "SELECT COUNT(*) FROM ad_candidate a JOIN store s ON s.store_id = a.store_id WHERE ABS(a.latitude - s.latitude) > 0.0000001 OR ABS(a.longitude - s.longitude) > 0.0000001 OR a.store_name <> s.name"},
            {"후보의 캠페인과 가게가 맞다", "SELECT COUNT(*) FROM ad_candidate a LEFT JOIN campaign c ON c.campaign_id = a.campaign_id AND c.store_id = a.store_id WHERE c.campaign_id IS NULL"},
            {"서빙 기록의 캠페인과 가게가 맞고 회원이 있다", "SELECT COUNT(*) FROM serve_log v LEFT JOIN campaign c ON c.campaign_id = v.campaign_id AND c.store_id = v.store_id LEFT JOIN member m ON m.member_id = v.member_id WHERE c.campaign_id IS NULL OR m.member_id IS NULL"},
            {"서빙 날짜가 캠페인 기간 안이다", "SELECT COUNT(*) FROM serve_log v JOIN campaign c ON c.campaign_id = v.campaign_id WHERE v.business_date < c.start_date OR v.business_date > c.end_date"},
            {"찜의 캠페인이 있다", "SELECT COUNT(*) FROM wishlist w LEFT JOIN campaign c ON c.campaign_id = w.campaign_id WHERE c.campaign_id IS NULL"},
            {"요약의 캠페인 상태와 할인이 캠페인과 같다", "SELECT COUNT(*) FROM store_campaign_summary x JOIN campaign c ON c.campaign_id = x.campaign_id WHERE x.store_id <> c.store_id OR x.discount_type <> c.discount_type OR x.discount_value <> c.discount_value"},
            {"예산 계획의 점주가 캠페인 점주와 같다", "SELECT COUNT(*) FROM budget_daily_plan b JOIN campaign c ON c.campaign_id = b.campaign_id WHERE b.owner_id <> c.owner_id"},
            {"원장의 캠페인 점주가 맞다", "SELECT COUNT(*) FROM point_ledger l JOIN campaign c ON c.campaign_id = l.campaign_id WHERE l.owner_id <> c.owner_id"},
            {"잔액이 음수인 점주가 없다", "SELECT COUNT(*) FROM (SELECT owner_id, SUM(amount) b FROM point_ledger WHERE entry_type <> 'DEDUCT' GROUP BY owner_id HAVING b < 0) x"},
            {"포인트 계정 없는 원장 점주가 없다", "SELECT COUNT(DISTINCT l.owner_id) FROM point_ledger l LEFT JOIN point_account a ON a.owner_id = l.owner_id WHERE a.owner_id IS NULL"},
            {"발급 쿠폰의 회원이 있다", "SELECT COUNT(*) FROM member_coupon mc LEFT JOIN member m ON m.member_id = mc.member_id WHERE m.member_id IS NULL"},
            {"최근 7일 리포트의 유효 노출이 노출 기록과 같다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN (SELECT campaign_id, business_date, COUNT(*) n FROM impression_log WHERE validity = 'VALID' GROUP BY campaign_id, business_date) i ON i.campaign_id = r.campaign_id AND i.business_date = r.business_date WHERE r.business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 7 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY) AND r.valid_impression_count <> COALESCE(i.n, 0)"},
            {"최근 7일 리포트의 차감액이 차감 원장과 같다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN (SELECT campaign_id, DATE(created_at) d, -SUM(amount) n FROM point_ledger WHERE entry_type = 'DEDUCT' GROUP BY campaign_id, DATE(created_at)) l ON l.campaign_id = r.campaign_id AND l.d = r.business_date WHERE r.business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 7 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY) AND r.charged_amount <> COALESCE(l.n, 0)"},
            {"리포트의 유효 노출은 과금과 미과금의 합이다", "SELECT COUNT(*) FROM campaign_daily_report WHERE valid_impression_count <> billable_impression_count + not_billed_impression_count"},
            {"리포트 캠페인이 있다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN campaign c ON c.campaign_id = r.campaign_id WHERE c.campaign_id IS NULL"},
            {"분석 날짜마다 집계 SUCCESS 가 있다", "SELECT 60 - COUNT(DISTINCT business_date) FROM batch_execution_log WHERE job_name = 'DAILY_AGGREGATION' AND status = 'SUCCESS' AND finished_at IS NOT NULL AND business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 60 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY)"},
            {"RUNNING 배치가 없다", "SELECT COUNT(*) FROM batch_execution_log WHERE status = 'RUNNING'"}
    };

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 빈_데이터베이스를_만든다() throws SQLException {
        String database = "world_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    @Test
    void 기본_세계가_빈_데이터베이스에_끝까지_들어간다() {
        new DomainFlywayMigrator(dataSource).afterPropertiesSet();
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", HASH,
                "demoAdminPasswordHash", HASH, "demoOwnerPasswordHash", HASH, "scenarioPasswordHash", HASH,
                "baseCdnBaseUrl", "https://cdn.example.com");
        long started = System.nanoTime();
        new DomainFlywayMigrator(dataSource, new BaseSettings(true, placeholders)).afterPropertiesSet();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name NOT LIKE 'flyway%' ORDER BY 1", String.class);
        long total = 0;
        StringBuilder report = new StringBuilder("\n[base-world] elapsedMs=" + elapsedMs + "\n");
        for (String table : tables) {
            long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            total += rows;
            if (rows > 0) {
                report.append("[base-world] ").append(table).append('=').append(rows).append('\n');
            }
        }
        report.append("[base-world] total=").append(total);
        System.out.println(report);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_history_base WHERE success = 0", Long.class)).isZero();

        /*
         * 파일끼리 같은 공식으로 계산한 값이 서로 맞는지 본다.
         * 어긋난 행 수를 모두 남기고 한 번에 판정한다.
         */
        StringBuilder mismatches = new StringBuilder();
        for (String[] check : CONSISTENCY) {
            long bad = jdbc.queryForObject(check[1], Long.class);
            System.out.println("[base-world] check " + check[0] + " = " + bad);
            if (bad > 0) {
                mismatches.append(check[0]).append('=').append(bad).append("; ");
            }
        }
        assertThat(mismatches.toString()).isEmpty();

        // 쓰지 않게 된 시간대별 목표 표는 시드 V8 이 지운다(#829)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'budget_hourly_target'", Long.class)).isZero();

        // 메뉴 할인 캠페인에는 정률과 정액이 둘 다 있어야 한다
        assertThat(jdbc.queryForList("SELECT DISTINCT discount_type FROM campaign "
                + "WHERE campaign_id >= 10000000 AND discount_target_type = 'MENU'", String.class))
                .containsExactlyInAnyOrder("PERCENT", "AMOUNT");
    }
}
