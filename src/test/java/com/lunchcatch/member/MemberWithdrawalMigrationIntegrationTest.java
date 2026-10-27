package com.lunchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MemberWithdrawalMigrationIntegrationTest extends AbstractMemberIntegrationTest {

    @Test
    void 기존_탈퇴_계정을_이력으로_옮기고_현재_회원번호의_유일성을_유지한다() throws Exception {
        String database = "member_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try {
                String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database;
                var source = new DriverManagerDataSource(url, "root", MYSQL.getPassword());
                Flyway.configure().dataSource(source).locations("classpath:db/migration/member")
                        .target(MigrationVersion.fromVersion("3")).load().migrate();
                JdbcTemplate jdbc = new JdbcTemplate(source);
                LocalDateTime withdrawn = LocalDateTime.of(2026, 10, 6, 13, 0);
                jdbc.update("""
                        INSERT INTO member (member_id, provider_user_id, nickname, status, withdrawn_at, suspended_at, updated_at)
                        VALUES (1, 'old-provider', '탈퇴한 회원', 'WITHDRAWN', ?, ?, ?),
                               (2, 'active-provider', '회원', 'ACTIVE', NULL, NULL, ?)
                        """, withdrawn, withdrawn.minusDays(1), withdrawn, withdrawn);
                jdbc.update("""
                        INSERT INTO kakao_unlink_failure (member_id, provider_user_id, updated_at)
                        VALUES (1, 'old-provider', ?)
                        """, withdrawn);

                Flyway.configure().dataSource(source).locations("classpath:db/migration/member").load().migrate();

                assertThat(jdbc.queryForObject("SELECT provider_user_id FROM member WHERE member_id = 1", String.class)).isNull();
                assertThat(jdbc.queryForObject("SELECT provider_user_id FROM member WHERE member_id = 2", String.class))
                        .isEqualTo("active-provider");
                assertThat(jdbc.queryForMap("SELECT provider_user_id, withdrawn_at, suspended_at FROM member_withdrawal_history WHERE member_id = 1"))
                        .containsEntry("provider_user_id", "old-provider");
                assertThat(jdbc.queryForObject("SELECT suspended_at FROM member_withdrawal_history WHERE member_id = 1", LocalDateTime.class))
                        .isEqualTo(withdrawn.minusDays(1));
                assertThat(jdbc.queryForObject("SELECT provider_user_id FROM kakao_unlink_failure WHERE member_id = 1", String.class))
                        .isEqualTo("old-provider");
                assertThat(jdbc.queryForObject("SELECT processing FROM kakao_unlink_failure WHERE member_id = 1", Boolean.class)).isFalse();
                for (String update : List.of(
                        "UPDATE member SET provider_user_id = NULL WHERE member_id = 2",
                        "UPDATE member SET provider_user_id = 'old-provider' WHERE member_id = 1")) {
                    assertThatThrownBy(() -> jdbc.update(update))
                            .isInstanceOfSatisfying(DataAccessException.class, failure ->
                                    assertThat(failure.getMostSpecificCause())
                                            .isInstanceOfSatisfying(SQLException.class, sqlFailure -> {
                                                assertThat(sqlFailure.getErrorCode()).isEqualTo(3819);
                                                assertThat(sqlFailure.getMessage()).contains("chk_member_provider_state");
                                            }));
                }
            } finally {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }
}
