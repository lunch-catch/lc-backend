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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/*
 * db/base 의 V1 이 기존 시험 시드가 넣은 행만 지우고 실제 데이터는 남기는지 본다 (#741 3절).
 *
 * 지운 db/seed 를 test 리소스(db/legacy-seed)에 그대로 두고 먼저 넣는다. 그다음 시드 행 일부를 실제 데이터처럼 바꿔
 * 시드가 9000번대를 직접 넣은 뒤 실제 가입자가 그 번호대를 받은 개발 서버와 같은 상태를 만든다.
 * 다른 통합 테스트와 같은 컨테이너를 쓰되 데이터베이스를 따로 만들고, 컨텍스트 없이 마이그레이터를 직접 돌린다.
 */
class BaseCleanupIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 빈_데이터베이스를_만든다() throws SQLException {
        String database = "base_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private void migrateSchema() {
        new DomainFlywayMigrator(dataSource).afterPropertiesSet();
    }

    // 예전 시드를 예전과 같은 이력 테이블로 넣는다. V1 이 그 이력 테이블도 지우는지 보려는 것이다
    private void insertLegacySeed() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/legacy-seed")
                .table("flyway_history_seed")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .placeholders(Map.of("seedPasswordHash", HASH, "seedBusinessDate", "2026-10-20",
                        "seedCdnBaseUrl", "https://cdn.example.com"))
                .load()
                .migrate();
    }

    private void migrateBase() {
        new DomainFlywayMigrator(dataSource, new BaseSettings(true, basePlaceholders())).afterPropertiesSet();
    }

    private static Map<String, String> basePlaceholders() {
        return Map.of("baseDate", "2026-10-28", "superadminPasswordHash", HASH, "demoAdminPasswordHash", HASH,
                "demoOwnerPasswordHash", HASH, "scenarioPasswordHash", HASH, "baseCdnBaseUrl", "https://cdn.example.com");
    }

    // V1 까지만 돌린다. 뒤의 기본 세계가 같은 테이블을 채우므로 테이블이 비었는지 보려면 V1 에서 멈춰야 한다
    private void migrateCleanupOnly() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/base")
                .table("flyway_history_base")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("1")
                .placeholders(basePlaceholders())
                .load()
                .migrate();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void 시드가_넣은_행은_지우고_시드_번호대의_실제_가입자는_남긴다() {
        migrateSchema();
        insertLegacySeed();
        // 실제 가입자가 시드 번호대를 받은 상태를 흉내 낸다: 회원 9050 과 점주 9017 을 실제 계정으로 바꾼다
        jdbc.update("UPDATE member SET provider_user_id = '5124551452' WHERE member_id = 9050");
        jdbc.update("UPDATE owner SET email = 'real-owner@example.com' WHERE owner_id = 9017");
        long realStoreCampaigns = count("SELECT COUNT(*) FROM campaign WHERE owner_id = 9017");
        assertThat(count("SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'seed-member-%'")).isPositive();

        migrateBase();

        assertThat(count("SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'seed-member-%'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM owner WHERE email LIKE '%@seed.lunchcatch.com'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM member WHERE member_id = 9050")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM member_profile WHERE member_id = 9050")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id = 9017")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM store WHERE owner_id = 9017")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE owner_id = 9017")).isEqualTo(realStoreCampaigns);
    }

    @Test
    void 시드_캠페인에_딸린_행은_모두_지운다() {
        migrateSchema();
        insertLegacySeed();
        // 개발 서버에서는 PG 대사가 시드 결제를 재검증 대기로 남길 수 있다. 결제를 지우기 전에 이 행이 먼저 지워져야 한다
        jdbc.update("INSERT INTO pg_charge_reconciliation_pending (payment_id, business_date, last_error, created_at, updated_at) "
                + "SELECT payment_id, '2026-10-20', 'PG 응답 없음', NOW(6), NOW(6) FROM payment ORDER BY payment_id LIMIT 1");

        migrateCleanupOnly();

        for (String table : new String[] {"ad_candidate", "budget_daily_plan", "serve_log", "impression_log", "swipe_log",
                "deduction_result", "coupon_event", "coupon_inventory", "member_coupon", "coupon_usage_history",
                "store_campaign_summary", "poster", "point_ledger", "payment", "pg_charge_reconciliation_pending", "refund_request", "settlement_mismatch",
                "daily_invalid_analytics", "daily_campaign_fraud_analytics", "event_log", "wishlist", "feed_filter",
                "store_menu", "store_image", "store_business_hour", "store"}) {
            assertThat(count("SELECT COUNT(*) FROM " + table)).as(table).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'flyway_history_seed'")).isZero();
    }

    @Test
    void 실제_포스터가_쓰는_시드_템플릿은_남긴다() {
        migrateSchema();
        insertLegacySeed();
        // 실제 점주 9017 의 캠페인 포스터가 시드 템플릿을 쓰는 상태를 만든다
        jdbc.update("UPDATE owner SET email = 'real-owner@example.com' WHERE owner_id = 9017");
        long campaign = count("SELECT campaign_id FROM campaign WHERE owner_id = 9017 ORDER BY campaign_id LIMIT 1");
        long template = count("SELECT template_id FROM template WHERE name = '시드 기본 템플릿'");
        jdbc.update("DELETE FROM poster WHERE campaign_id = ?", campaign);
        jdbc.update("INSERT INTO poster (campaign_id, template_id, title, slot_values, html_content, image_object_key, "
                + "created_at, updated_at) SELECT ?, ?, '실제 포스터', slot_values, html_content, image_object_key, NOW(6), NOW(6) "
                + "FROM poster ORDER BY poster_id LIMIT 1", campaign, template);

        migrateBase();

        assertThat(count("SELECT COUNT(*) FROM template WHERE template_id = ?", template)).isEqualTo(1);
        assertThat(count("SELECT current_count FROM template_quota")).isEqualTo(count("SELECT COUNT(*) FROM template"));
    }

    @Test
    void 시드가_돈_적_없는_데이터베이스에서는_아무것도_지우지_않는다() {
        migrateSchema();
        jdbc.update("INSERT INTO admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                + "VALUES ('superadmin', ?, '최고관리자', 'SUPER_ADMIN', 'ACTIVE', NOW(6), NOW(6))", HASH);

        migrateBase();

        assertThat(count("SELECT COUNT(*) FROM admin WHERE login_id = 'superadmin'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM flyway_history_base WHERE script = 'V1__remove_test_seed.sql' AND success = 1"))
                .isEqualTo(1);
    }
}
