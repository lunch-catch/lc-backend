package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.global.config.DomainFlywayMigrator.SeedSettings;
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
 * 시드(db/seed)가 실제 MySQL 에 들어가는지, 다시 돌아도 행이 늘지 않는지, 날짜가 바뀌면 그날 행이 새로 생기는지 본다.
 *
 * 다른 통합 테스트와 같은 컨테이너를 쓰되 데이터베이스를 따로 만든다. 같은 데이터베이스에 시드를 넣으면
 * 다른 테스트가 보는 행이 바뀐다. 컨텍스트를 띄우지 않고 마이그레이터를 직접 돌린다.
 */
class SeedIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 빈_데이터베이스를_만든다() throws SQLException {
        String database = "seed_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("CREATE DATABASE " + database
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + database)
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=Asia/Seoul&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
        dataSource = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    private void migrate(boolean seedEnabled, String businessDate) {
        Map<String, String> placeholders = Map.of("seedPasswordHash", HASH, "seedBusinessDate", businessDate);
        new DomainFlywayMigrator(dataSource, new SeedSettings(seedEnabled, placeholders)).afterPropertiesSet();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    @Test
    void 시드를_켜면_계정과_가게와_오늘의_피드_행이_들어간다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM admin WHERE login_id = 'superadmin' AND role = 'SUPER_ADMIN'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id IN (9001, 9002) AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM store WHERE store_id IN (9001, 9002) AND finalized = 1")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id IN (9001, 9002) AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM member_profile WHERE onboarding_completed_at IS NOT NULL")).isEqualTo(2);
        // 피드 시험용 9003~9016 이 더 있다. 9016 은 예산 계획이 없는 후보라 예산은 하나 적다
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 9001 AND 9016 AND status = 'ACTIVE'")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM poster WHERE campaign_id BETWEEN 9001 AND 9016")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-15' AND campaign_state = 'SERVABLE'")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM budget_daily_plan WHERE business_date = '2026-10-15'")).isEqualTo(15);
        assertThat(count("SELECT COUNT(*) FROM coupon_inventory")).isEqualTo(100);
        assertThat(count("SELECT current_count FROM template_quota")).isEqualTo(1);
    }

    @Test
    void 시드를_켜면_결제와_원장과_환불과_대사_불일치가_들어가고_잔액이_맞는다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM payment WHERE payment_id < 9100")).isEqualTo(12);
        assertThat(count("SELECT COUNT(*) FROM payment_reconciliation_outbox")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM refund_request")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM settlement_mismatch")).isEqualTo(6);
        assertThat(count("SELECT COUNT(DISTINCT check_type) FROM settlement_mismatch")).isEqualTo(3);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE owner_id = 9013 AND entry_type <> 'DEDUCT'"))
                .isEqualTo(88200);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE owner_id = 9014 AND entry_type <> 'DEDUCT'"))
                .isEqualTo(57000);
        assertThat(count("SELECT COALESCE(SUM(amount), 0) FROM point_ledger WHERE owner_id = 9015 AND entry_type <> 'DEDUCT'"))
                .isEqualTo(10000);
        // 대기 중인 환불 요청 금액은 그 점주의 잔액 전액이다
        assertThat(count("SELECT amount FROM refund_request WHERE status = 'REQUESTED'")).isEqualTo(57000);
    }

    @Test
    void 광고_서빙_시드의_유효_노출마다_차감_판정과_원장_차감이_하나씩_있다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM deduction_result WHERE billing_status = 'BILLED' AND unit_price = 10"))
                .isEqualTo(90);
        assertThat(count("SELECT COUNT(*) FROM point_ledger l JOIN deduction_result r ON r.serve_id = l.serve_id "
                + "WHERE l.entry_type = 'DEDUCT' AND l.amount = -r.unit_price AND l.owner_id = r.owner_id "
                + "AND l.campaign_id = r.campaign_id")).isEqualTo(90);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE entry_type = 'DEDUCT' AND owner_id BETWEEN 9001 AND 9012"))
                .isEqualTo(90);
        // 자리 10 은 무효 노출이라 정산 기록이 없다
        assertThat(count("WITH RECURSIVE seq (n) AS (SELECT 9001 UNION ALL SELECT n + 1 FROM seq WHERE n < 9010) "
                + "SELECT COUNT(*) FROM deduction_result WHERE serve_id IN (SELECT UNHEX(MD5(CONCAT('seed-serve-', n, '-', "
                + "DATE_SUB('2026-10-15', INTERVAL MOD(n, 6) + 1 DAY), '-10'))) FROM seq)")).isZero();
    }

    @Test
    void 노출이_닿은_캠페인과_영업일마다_계획과_예약과_해제가_있고_하루의_합이_영이다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM budget_daily_plan WHERE campaign_id BETWEEN 9001 AND 9012 "
                + "AND business_date < '2026-10-15'")).isEqualTo(66);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE owner_id BETWEEN 9001 AND 9012 AND entry_type = 'RESERVE'"))
                .isEqualTo(66);
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE owner_id BETWEEN 9001 AND 9012 AND entry_type = 'RELEASE'"))
                .isEqualTo(66);
        // DEDUCT 는 예약에서 이미 빠진 금액을 기록한 행이라 부호를 뒤집어 더한다(RESERVE - DEDUCT + RELEASE = 0)
        assertThat(count("SELECT COUNT(*) FROM (SELECT campaign_id, COALESCE(business_date, DATE(created_at)) AS day, "
                + "SUM(IF(entry_type = 'DEDUCT', -amount, amount)) AS total FROM point_ledger "
                + "WHERE owner_id BETWEEN 9001 AND 9012 "
                + "AND entry_type IN ('RESERVE', 'DEDUCT', 'RELEASE') "
                + "GROUP BY campaign_id, COALESCE(business_date, DATE(created_at))) t WHERE total <> 0")).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_account WHERE owner_id BETWEEN 9001 AND 9012")).isEqualTo(12);
        // 점주마다 충전이 있어 잔액이 0 이하로 내려가지 않는다
        assertThat(count("SELECT COUNT(*) FROM (SELECT owner_id, SUM(amount) AS balance FROM point_ledger "
                + "WHERE owner_id BETWEEN 9001 AND 9012 AND entry_type <> 'DEDUCT' GROUP BY owner_id) t "
                + "WHERE balance <= 0")).isZero();
    }

    @Test
    void 같은_날_다시_돌면_행이_늘지_않는다() {
        migrate(true, "2026-10-15");
        long before = count("SELECT (SELECT COUNT(*) FROM coupon_inventory) + (SELECT COUNT(*) FROM ad_candidate) "
                + "+ (SELECT COUNT(*) FROM deduction_result) + (SELECT COUNT(*) FROM point_ledger)");

        migrate(true, "2026-10-15");

        assertThat(count("SELECT (SELECT COUNT(*) FROM coupon_inventory) + (SELECT COUNT(*) FROM ad_candidate) "
                + "+ (SELECT COUNT(*) FROM deduction_result) + (SELECT COUNT(*) FROM point_ledger)"))
                .isEqualTo(before);
    }

    @Test
    void 날짜가_바뀌면_그날의_행만_새로_생긴다() {
        migrate(true, "2026-10-15");

        migrate(true, "2026-10-16");

        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-16'")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM coupon_event")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM owner")).isEqualTo(18);
    }

    @Test
    void 시드를_끄면_시드_이력도_행도_없다() {
        migrate(false, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'flyway_history_seed'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM owner")).isZero();
    }
}
