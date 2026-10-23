package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.global.config.DomainFlywayMigrator.BaseSettings;
import java.nio.file.Path;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/*
 * 기본 세계를 다시 넣는 정리(scripts/reset_base_world.sql)가 계정과 실제 데이터를 남기는지 본다 (#858).
 * 한 기준일로 넣은 DB 에 기본 세계 뒤에 가입한 실제 회원과 점주를 더하고, 정리 뒤 다른 기준일로 다시 넣는다.
 * 실제 가입자는 자동 증가 번호가 시연 대역 위(2,000만 이상)라 그 번호대로 흉내 낸다.
 */
class BaseWorldResetIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";
    private static final long REAL_MEMBER = 20_000_300L;
    private static final long REAL_OWNER = 20_000_030L;
    private static final long OLD_MEMBER = 3L;
    private static final long BAND_OWNER = 20_000_031L;
    private static final long BAND_STORE = 10_000_301L;

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void 빈_데이터베이스를_만든다() throws SQLException {
        String database = "reset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private void migrate(String baseDate) {
        Map<String, String> placeholders = Map.of("baseDate", baseDate, "superadminPasswordHash", HASH,
                "demoAdminPasswordHash", HASH, "demoOwnerPasswordHash", HASH, "scenarioPasswordHash", HASH,
                "baseCdnBaseUrl", "https://cdn.example.com");
        new DomainFlywayMigrator(dataSource, new BaseSettings(true, placeholders)).afterPropertiesSet();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // 기본 세계 뒤에 실제로 가입한 회원과 점주, 그 점주의 가게와 캠페인이다
    private void addRealData() {
        jdbc.update("INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at) "
                + "VALUES (?, 'real-kakao-1', '실제회원', 'ACTIVE', NOW(6), NOW(6))", REAL_MEMBER);
        jdbc.update("INSERT INTO member_profile (member_id, gender, age_group, latitude, longitude, onboarding_completed_at, "
                + "created_at, updated_at) VALUES (?, 'MALE', 'AGE_20S', 37.5, 127.03, NOW(6), NOW(6), NOW(6))", REAL_MEMBER);
        jdbc.update("INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at) "
                + "VALUES (?, 'real-kakao-old', '예전회원', 'ACTIVE', NOW(6), NOW(6))", OLD_MEMBER);
        jdbc.update("INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at) "
                + "VALUES (?, 'real-owner@example.com', ?, 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))", REAL_OWNER, HASH);
        jdbc.update("INSERT INTO store (store_id, owner_id, name, category_code, representative_name, road_address, latitude, longitude, "
                + "business_registration_number, business_verification_status, business_verified_at, finalized, finalized_at, "
                + "created_at, updated_at) VALUES (?, ?, '실제 가게', 'KOREAN', '실제대표', '서울 강남구 실제로 1', 37.5, 127.03, '1234567890', "
                + "'VERIFIED', NOW(6), 1, NOW(6), NOW(6), NOW(6))", REAL_OWNER, REAL_OWNER);
        jdbc.update("INSERT INTO campaign (campaign_id, owner_id, store_id, status, status_changed_at, discount_target_type, "
                + "discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time, target_radius, target_gender, "
                + "target_age_groups, created_at, updated_at) VALUES (?, ?, ?, 'DRAFT', NOW(6), 'ALL', 'AMOUNT', 1000, 10, "
                + "'11:30:00', '13:30:00', 1000, 'ALL', 'AGE_20S', NOW(6), NOW(6))", 20_000_600L, REAL_OWNER, REAL_OWNER);
        // 실제 캠페인도 2,000만 대역 번호를 받는다. 그 포스터는 시연 포스터로 바뀌지 않는다
        jdbc.update("INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at, "
                + "image_object_key) VALUES (?, ?, 10000001, '실제 포스터', JSON_OBJECT(), '<div>실제</div>', NOW(6), NOW(6), "
                + "'posters/real.jpg')", 20_000_600L, 20_000_600L);
        /*
         * 기본 세계 번호를 이미 받은 실제 가게다 (개발의 가게 10000301).
         * 기본 세계 가게 301 과 그 메뉴, 이미지, 영업시간을 실제 점주의 가게로 바꿔 흉내 낸다.
         */
        jdbc.update("INSERT INTO owner (owner_id, email, password_hash, role, status, tutorial_viewed, created_at, updated_at) "
                + "VALUES (?, 'real-owner-2@example.com', ?, 'OWNER', 'ACTIVE', 1, NOW(6), NOW(6))", BAND_OWNER, HASH);
        jdbc.update("UPDATE store SET owner_id = ?, name = '번호 겹친 실제 가게' WHERE store_id = ?", BAND_OWNER, BAND_STORE);
    }

    private List<Map<String, Object>> accounts() {
        return jdbc.queryForList("SELECT 'admin' k, admin_id id, login_id v, password_hash h FROM admin "
                + "UNION ALL SELECT 'owner', owner_id, email, password_hash FROM owner "
                + "UNION ALL SELECT 'member', member_id, provider_user_id, status FROM member ORDER BY 1, 2");
    }

    @Test
    void 정리하고_다른_기준일로_다시_넣어도_계정과_실제_데이터는_남는다() {
        migrate("2026-10-20");
        addRealData();
        List<Map<String, Object>> before = accounts();

        ResourceDatabasePopulator reset = new ResourceDatabasePopulator(new FileSystemResource(Path.of("scripts/reset_base_world.sql")));
        reset.execute(dataSource);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 10000001 AND 19999999")).isZero();
        assertThat(count("SELECT COUNT(*) FROM flyway_history_base WHERE version = '1'")).isEqualTo(1);

        migrate("2026-10-28");

        // 계정은 하나도 바뀌지 않고 새 점주 301 ~ 500 만 늘었다 (정리 전에도 이미 있었다)
        assertThat(accounts()).isEqualTo(before);
        // 실제 회원의 프로필, 실제 점주의 가게와 캠페인이 남는다
        assertThat(count("SELECT COUNT(*) FROM member_profile WHERE member_id = ?", REAL_MEMBER)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM store WHERE store_id = ?", REAL_OWNER)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id = 20000600")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM poster WHERE campaign_id = 20000600 AND title = '실제 포스터' "
                + "AND image_object_key = 'posters/real.jpg'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM poster_moderation_result WHERE poster_id = 20000600")).isZero();
        // 번호가 겹친 실제 가게는 메뉴, 이미지, 영업시간과 함께 4,000만을 더한 번호로 옮겨졌다
        assertThat(count("SELECT COUNT(*) FROM store WHERE store_id = ? AND owner_id = ?", BAND_STORE + 40_000_000L, BAND_OWNER))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM store_menu WHERE store_id = ? AND store_menu_id > 50000000", BAND_STORE + 40_000_000L))
                .isPositive();
        assertThat(count("SELECT COUNT(*) FROM store_business_hour WHERE store_id = ? AND store_business_hour_id > 50000000",
                BAND_STORE + 40_000_000L)).isEqualTo(7);
        // 그 자리에는 기본 세계 가게 301 이 좌표와 함께 다시 들어갔다
        assertThat(count("SELECT COUNT(*) FROM store WHERE store_id = ? AND owner_id = ? AND latitude IS NOT NULL",
                BAND_STORE, BAND_STORE)).isEqualTo(1);
        /*
         * 기본 세계는 새 기준일로 다시 들어갔다.
         * 오늘 후보는 역 캠페인 210개와 오늘 끝나는 역 밖 캠페인 341 이다.
         */
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-28' AND campaign_id BETWEEN 10000001 AND 19999999"))
                .isEqualTo(211);
        /*
         * 예전 기준일(10-20)의 오늘 후보는 남지 않는다.
         * 캠페인 1 의 D-8 후보(ADS.CANDIDATE_OLD)는 새 기준일에서도 10-20 이다.
         */
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-20' AND campaign_id <> 10000001")).isZero();
        assertThat(count("SELECT COUNT(*) FROM flyway_history_base WHERE success = 0")).isZero();
        assertThat(BaseWorldIntegrationTest.consistencyViolations(jdbc)).isEmpty();
    }
}
