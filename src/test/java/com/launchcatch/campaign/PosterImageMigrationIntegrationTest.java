package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V1의 실제 스키마와 데이터를 V2로 올려 MySQL의 DDL과 제약을 검증한다. */
@Testcontainers
@SpringBootTest(properties = "logging.level.root=WARN")
class PosterImageMigrationIntegrationTest {

    // 애플리케이션 스키마와 분리된 시험 스키마를 만들고 지울 권한이 필요하다.
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withUsername("root");

    private static final String SCHEMA = "poster_image_migration";
    private static final String IMAGE_KEY = "menus/42/점심.jpg";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> "test-only-secret-not-used-anywhere-else-0123456789abcdef");
    }

    @Autowired
    private DataSource dataSource;

    // 마이그레이션 검증 중 카카오 OIDC discovery 서버에 접속하지 않는다.
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean(name = "kakaoJwtDecoder")
    private JwtDecoder kakaoJwtDecoder;

    private DataSource migrationDataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 이전_버전의_독립된_스키마를_준비한다() {
        new JdbcTemplate(dataSource).execute("CREATE DATABASE " + SCHEMA
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + SCHEMA),
                MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(migrationDataSource);
        migration("1").migrate();
        jdbc.update("""
                INSERT INTO template (template_id, name, status, active, created_at, updated_at)
                VALUES (1, '시험 템플릿', 'DRAFT', false, '2026-10-01 12:00:00', '2026-10-01 12:00:00')
                """);
        // 포스터마다 서로 다른 캠페인이 필요하다(uk_poster_campaign_id).
        jdbc.update("""
                INSERT INTO campaign (
                    campaign_id, owner_id, store_id, status_changed_at, discount_target_type,
                    discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time,
                    target_age_groups, daily_budget, start_date, end_date, created_at, updated_at)
                VALUES
                    (1, 1, 1, '2026-10-01 12:00:00', 'ALL', 'AMOUNT', 1000, 10, '11:00:00', '14:00:00',
                     'AGE_20S', 10000, '2026-10-01', '2026-10-02', '2026-10-01 12:00:00', '2026-10-01 12:00:00'),
                    (2, 1, 1, '2026-10-01 12:00:00', 'ALL', 'AMOUNT', 1000, 10, '11:00:00', '14:00:00',
                     'AGE_20S', 10000, '2026-10-01', '2026-10-02', '2026-10-01 12:00:00', '2026-10-01 12:00:00')
                """);
    }

    @AfterEach
    void 시험_스키마를_지운다() {
        // MySQL DDL은 트랜잭션 롤백으로 격리되지 않으므로 스키마를 통째로 지운다.
        new JdbcTemplate(dataSource).execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    @Test
    void 빈_포스터_테이블의_이미지_컬럼을_필수_객체_키로_바꾼다() {
        // when
        migration("2").migrate();

        // then
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'poster'
                  AND column_name IN ('poster_image', 'image_object_key')
                """, String.class)).containsExactly("image_object_key");
        var column = jdbc.queryForMap("""
                SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_COMMENT
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'poster' AND column_name = 'image_object_key'
                """);
        assertThat(((Number) column.get("CHARACTER_MAXIMUM_LENGTH")).longValue()).isEqualTo(255);
        assertThat(column)
                .containsEntry("DATA_TYPE", "varchar")
                .containsEntry("IS_NULLABLE", "NO")
                .containsEntry("COLUMN_COMMENT", "포스터 메뉴 이미지의 객체 스토리지 키. URL 은 응답에서 cdn.base-url 과 붙여 만든다");
    }

    @Test
    void 객체_키_전체에_비고유_조회_인덱스를_만든다() {
        // when
        migration("2").migrate();

        // then
        assertThat(jdbc.queryForList("""
                SELECT COLUMN_NAME, NON_UNIQUE, SEQ_IN_INDEX, SUB_PART
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'poster'
                  AND index_name = 'idx_poster_image_object_key'
                """))
                .singleElement().satisfies(index -> {
                    assertThat(index).containsEntry("COLUMN_NAME", "image_object_key");
                    assertThat(((Number) index.get("NON_UNIQUE")).intValue()).isOne();
                    assertThat(((Number) index.get("SEQ_IN_INDEX")).intValue()).isOne();
                    assertThat(index.get("SUB_PART")).isNull();
                });
    }

    @Test
    void 기존_객체_키와_포스터_내용을_보존한다() {
        // given
        insertPoster("poster_image", 1, IMAGE_KEY);
        var before = jdbc.queryForMap("SELECT * FROM poster WHERE poster_id = 1");
        before.remove("poster_image");
        before.put("image_object_key", IMAGE_KEY);

        // when
        migration("2").migrate();

        // then
        assertThat(jdbc.queryForMap("SELECT * FROM poster WHERE poster_id = 1")).isEqualTo(before);
    }

    @Test
    void 기존_포스터들이_같은_객체_키를_사용해도_마이그레이션된다() {
        // given
        insertPoster("poster_image", 1, IMAGE_KEY);
        insertPoster("poster_image", 2, IMAGE_KEY);

        // when
        migration("2").migrate();

        // then
        assertThat(jdbc.queryForList("SELECT image_object_key FROM poster ORDER BY poster_id", String.class))
                .containsExactly(IMAGE_KEY, IMAGE_KEY);
    }

    @Test
    void 기존_NULL_이미지는_임의의_키로_변환하지_않고_마이그레이션을_거부한다() {
        // given
        insertPoster("poster_image", 1, null);

        // when, then
        assertThatThrownBy(() -> migration("2").migrate())
                .isInstanceOf(FlywayException.class)
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster WHERE poster_image IS NULL", Integer.class))
                .isOne();
    }

    @Test
    void 여러_포스터에_같은_객체_키를_저장하고_조회한다() {
        // given
        migration("2").migrate();

        // when
        insertPoster("image_object_key", 1, IMAGE_KEY);
        insertPoster("image_object_key", 2, IMAGE_KEY);

        // then
        assertThat(jdbc.queryForList("SELECT poster_id FROM poster WHERE image_object_key = ? ORDER BY poster_id",
                Long.class, IMAGE_KEY)).containsExactly(1L, 2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한"})
    void 객체_키는_255자까지_잘리지_않고_저장된다(String character) {
        // given
        migration("2").migrate();
        String key = character.repeat(255);

        // when
        insertPoster("image_object_key", 1, key);

        // then
        assertThat(jdbc.queryForObject("SELECT image_object_key FROM poster WHERE poster_id = 1", String.class))
                .isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한"})
    void 객체_키가_256자이면_저장을_거부한다(String character) {
        // given
        migration("2").migrate();

        // when, then
        assertThatThrownBy(() -> insertPoster("image_object_key", 1, character.repeat(256)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(1406));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster", Integer.class)).isZero();
    }

    @Test
    void NULL_객체_키의_저장을_거부한다() {
        // given
        migration("2").migrate();

        // when, then
        assertThatThrownBy(() -> insertPoster("image_object_key", 1, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(1048));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster", Integer.class)).isZero();
    }

    @Test
    void 객체_키를_생략하면_기본값으로_채우지_않고_저장을_거부한다() {
        // given
        migration("2").migrate();

        // when, then
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values,
                                    html_content, created_at, updated_at)
                VALUES (1, 1, 1, '점심 포스터', '{}', '<p>점심</p>',
                        '2026-10-01 12:00:00', '2026-10-01 12:00:00')
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(1364));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster", Integer.class)).isZero();
    }

    @Test
    void 기존_객체_키를_NULL로_바꾸는_수정을_거부한다() {
        // given
        migration("2").migrate();
        insertPoster("image_object_key", 1, IMAGE_KEY);

        // when, then
        assertThatThrownBy(() -> jdbc.update("UPDATE poster SET image_object_key = NULL WHERE poster_id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(1048));
        assertThat(jdbc.queryForObject("SELECT image_object_key FROM poster WHERE poster_id = 1", String.class))
                .isEqualTo(IMAGE_KEY);
    }

    private Flyway migration(String target) {
        return Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration/campaign")
                .table("flyway_history_campaign")
                .target(target)
                .load();
    }

    private void insertPoster(String imageColumn, long id, String imageKey) {
        // 컬럼 이름은 이 클래스 안의 두 상수 문자열만 받는다. 값은 모두 바인딩한다.
        jdbc.update("""
                INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values,
                                    html_content, created_at, updated_at, %s)
                VALUES (?, ?, 1, '점심 포스터', '{}', '<p>점심</p>',
                        '2026-10-01 12:00:00', '2026-10-01 12:00:00', ?)
                """.formatted(imageColumn), id, id, imageKey);
    }
}
