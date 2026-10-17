package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class OwnerTermsAgreementMigrationIntegrationTest extends AbstractStoreIntegrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void 기존_동의는_점주별로_합치고_미동의와_철회를_완료로_승격하지_않는다() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                var schema = new ClassPathResource("db/migration/store/V1__store_init.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
                var table = schema.substring(schema.indexOf("CREATE TABLE owner_terms_agreement"))
                        .replace("CREATE TABLE owner_terms_agreement", "CREATE TEMPORARY TABLE owner_terms_agreement");
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ByteArrayResource(table.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
                statement.executeUpdate("""
                        INSERT INTO owner_terms_agreement
                        (owner_id, terms_type, terms_version, required, agreed, withdrawn_at, created_at, updated_at) VALUES
                        (7, 'SERVICE', '1.0', true, true, NULL, '2026-10-01', '2026-10-02'),
                        (7, 'PRIVACY', '1.0', true, true, NULL, '2026-10-01', '2026-10-03'),
                        (8, 'SERVICE', '1.0', true, true, NULL, '2026-10-01', '2026-10-02'),
                        (8, 'PRIVACY', '1.0', true, false, NULL, '2026-10-01', '2026-10-03'),
                        (9, 'SERVICE', '1.0', true, true, '2026-10-04', '2026-10-01', '2026-10-04')
                        """);

                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/store/V5__simplify_owner_terms_agreement.sql"));

                try (var rows = statement.executeQuery("SELECT owner_id, required, agreed, agreed_at, withdrawn_at FROM owner_terms_agreement ORDER BY owner_id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("owner_id")).isEqualTo(7);
                    assertThat(rows.getBoolean("required")).isTrue();
                    assertThat(rows.getBoolean("agreed")).isTrue();
                    assertThat(rows.getTimestamp("agreed_at").toLocalDateTime())
                            .isEqualTo(java.time.LocalDateTime.of(2026, 10, 3, 0, 0));
                    assertThat(rows.getTimestamp("withdrawn_at")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("owner_id")).isEqualTo(8);
                    assertThat(rows.getBoolean("required")).isTrue();
                    assertThat(rows.getBoolean("agreed")).isFalse();
                    assertThat(rows.getTimestamp("agreed_at")).isNull();
                    assertThat(rows.getTimestamp("withdrawn_at")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("owner_id")).isEqualTo(9);
                    assertThat(rows.getBoolean("required")).isTrue();
                    assertThat(rows.getBoolean("agreed")).isFalse();
                    assertThat(rows.getTimestamp("agreed_at")).isNull();
                    assertThat(rows.getTimestamp("withdrawn_at")).isNotNull();
                    assertThat(rows.next()).isFalse();
                }
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO owner_terms_agreement (owner_id, required, agreed, agreed_at, created_at, updated_at)
                        VALUES (7, true, true, '2026-10-17', '2026-10-17', '2026-10-17')
                        """)).isInstanceOf(SQLException.class);
            } finally {
                statement.execute("DROP TEMPORARY TABLE IF EXISTS owner_terms_agreement_snapshot");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS owner_terms_agreement");
            }
        }
    }
}
