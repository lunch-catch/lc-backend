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
    }
}
