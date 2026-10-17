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
        assertThat(count("SELECT COUNT(*) FROM coupon_inventory")).isEqualTo(104);
        assertThat(count("SELECT current_count FROM template_quota")).isEqualTo(1);
    }

    @Test
    void 같은_날_다시_돌면_행이_늘지_않는다() {
        migrate(true, "2026-10-15");
        long before = count("SELECT (SELECT COUNT(*) FROM coupon_inventory) + (SELECT COUNT(*) FROM ad_candidate)");

        migrate(true, "2026-10-15");

        assertThat(count("SELECT (SELECT COUNT(*) FROM coupon_inventory) + (SELECT COUNT(*) FROM ad_candidate)"))
                .isEqualTo(before);
    }

    @Test
    void 날짜가_바뀌면_그날의_행만_새로_생긴다() {
        migrate(true, "2026-10-15");

        migrate(true, "2026-10-16");

        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-16'")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM coupon_event")).isEqualTo(8);
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
