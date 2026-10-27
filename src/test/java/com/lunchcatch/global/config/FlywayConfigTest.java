package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import org.junit.jupiter.api.Test;

class FlywayConfigTest {

    /*
     * 마이그레이션은 앱 연결의 socketTimeout(10초)에 걸리지 않는 따로 만든 연결로 돈다(#1010).
     * 큰 테이블의 DDL 을 클라이언트가 먼저 포기하면 서버의 DDL 은 끝나고 Flyway 만 실패로 남는다.
     */
    @Test
    void 마이그레이션_연결은_앱과_같은_주소와_계정에_소켓_시간_제한만_30분이다() {
        var details = new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return "user";
            }

            @Override
            public String getPassword() {
                return "secret";
            }

            @Override
            public String getJdbcUrl() {
                return "jdbc:mysql://db:3306/lunchcatch";
            }
        };

        var dataSource = (DriverManagerDataSource) FlywayConfig.migrationDataSource(details);

        assertThat(dataSource.getUrl()).isEqualTo("jdbc:mysql://db:3306/lunchcatch");
        assertThat(dataSource.getUsername()).isEqualTo("user");
        assertThat(dataSource.getConnectionProperties())
                .containsEntry("socketTimeout", "1800000")
                .containsEntry("connectTimeout", "3000");
    }

    @Test
    void 끝의_슬래시를_모두_뗀다() {
        assertThat(FlywayConfig.withoutTrailingSlash("https://cdn.dev.lunchcatch.com//"))
                .isEqualTo("https://cdn.dev.lunchcatch.com");
    }

    @Test
    void 슬래시가_없으면_그대로_둔다() {
        assertThat(FlywayConfig.withoutTrailingSlash("https://cdn.dev.lunchcatch.com"))
                .isEqualTo("https://cdn.dev.lunchcatch.com");
    }

    @Test
    void 빈_값은_빈_값이다() {
        assertThat(FlywayConfig.withoutTrailingSlash("")).isEmpty();
    }

    @Test
    void 켜져_있는데_비밀번호_해시가_비면_기동을_막는다() {
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", "$2a$10$x",
                "demoAdminPasswordHash", "", "demoOwnerPasswordHash", "$2a$10$x", "scenarioPasswordHash", "$2a$10$x");
        assertThatThrownBy(() -> FlywayConfig.requireHashes(placeholders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demoAdminPasswordHash");
    }

    @Test
    void 해시가_모두_있으면_통과한다() {
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", "$2a$10$x",
                "demoAdminPasswordHash", "$2a$10$x", "demoOwnerPasswordHash", "$2a$10$x", "scenarioPasswordHash", "$2a$10$x");
        assertThatCode(() -> FlywayConfig.requireHashes(placeholders)).doesNotThrowAnyException();
    }
}
