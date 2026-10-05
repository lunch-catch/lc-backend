package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/*
 * 도메인별 마이그레이션이 실제 MySQL 에 모두 적용되는지 본다.
 *
 * 단위 테스트로는 닿지 않는 것이 둘이다. 도메인마다 이력 테이블을 따로 주는 방식이 Flyway 에서
 * 통하는지, 그리고 마이그레이션이 엔티티 매니저보다 먼저 도는지다. 둘 중 하나라도 어긋나면
 * 기동이 깨지거나 테이블이 조용히 빠지는데, 그 순간은 운영 첫 배포다.
 *
 * 기대값을 숫자로 적지 않는다. 마이그레이션 파일의 CREATE TABLE 을 세어 견준다. 숫자로 적으면
 * 테이블을 더한 사람이 이 파일도 함께 고쳐야 하고, 잊으면 멀쩡한 변경이 빨갛게 뜬다.
 */
@Testcontainers
@SpringBootTest
class FlywayMigrationIntegrationTest {

    private static final Path MIGRATION_DIR =
            Path.of("src/main/resources", DomainFlywayMigrator.ROOT);

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE(?: IF NOT EXISTS)?\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    /** 운영 비밀이 아니라 컨텍스트를 띄우기 위한 자리 채움이다. */
    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private DataSource dataSource;

    private static Stream<String> migrationBodies() {
        try (Stream<Path> paths = Files.walk(MIGRATION_DIR)) {
            return paths.filter(path -> path.toString().endsWith(".sql"))
                    .map(FlywayMigrationIntegrationTest::read)
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long declaredTableCount() {
        return migrationBodies()
                .flatMap(body -> CREATE_TABLE.matcher(body).results())
                .map(result -> result.group(1))
                .distinct()
                .count();
    }

    private long countTables(String condition) {
        Integer count = new JdbcTemplate(dataSource).queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name %s 'flyway_history_%%'
                """.formatted(condition), Integer.class);
        return count == null ? -1 : count;
    }

    @Test
    @DisplayName("기동하면 도메인마다 이력 테이블이 하나씩 생긴다")
    void 이력_테이블이_도메인마다_생긴다() {
        assertThat(countTables("LIKE"))
                .as("flyway_history_* 테이블")
                .isEqualTo(DomainFlywayMigrator.domains().size());
    }

    /*
     * 이력 테이블만 생기고 업무 테이블이 빠지는 경우를 잡는다.
     * baselineVersion 을 0 으로 내리지 않으면 정확히 그 모습이 된다. 각 도메인의 V1 이 이미
     * 적용된 것으로 간주되어 건너뛰어지고, 앱은 아무 오류 없이 뜬다.
     */
    @Test
    @DisplayName("마이그레이션이 선언한 테이블이 모두 만들어진다")
    void 선언한_테이블이_모두_생긴다() {
        assertThat(countTables("NOT LIKE"))
                .as("업무 테이블")
                .isEqualTo(declaredTableCount());
    }
}
