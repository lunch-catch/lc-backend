package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.global.config.DomainFlywayMigrator.SeedSettings;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/*
 * 시드(db/seed)가 실제 MySQL 에 들어가는지, 다시 돌아도 행이 늘지 않는지, 날짜가 바뀌면 그날 행이 새로 생기는지 본다.
 *
 * 다른 통합 테스트와 같은 컨테이너를 쓰되 데이터베이스를 따로 만든다. 같은 데이터베이스에 시드를 넣으면
 * 다른 테스트가 보는 행이 바뀐다. 컨텍스트를 띄우지 않고 마이그레이터를 직접 돌린다.
 */
class SeedIntegrationTest extends IntegrationTestSupport {

    private static final String HASH = "$2a$10$MRk.eo1/rGQNUGClHmwVB.Z5zREkJzDzDfu9M4oJsZ.vd0s3n6tAu";

    private static final String ROW_COUNTS = "SELECT (SELECT COUNT(*) FROM coupon_inventory) + (SELECT COUNT(*) FROM ad_candidate) "
            + "+ (SELECT COUNT(*) FROM serve_log) + (SELECT COUNT(*) FROM impression_log) + (SELECT COUNT(*) FROM swipe_log) "
            + "+ (SELECT COUNT(*) FROM wishlist) + (SELECT COUNT(*) FROM feed_visit_daily) + (SELECT COUNT(*) FROM feed_filter) "
            + "+ (SELECT COUNT(*) FROM feed_filter_category) + (SELECT COUNT(*) FROM member_profile) "
            + "+ (SELECT COUNT(*) FROM deduction_result) + (SELECT COUNT(*) FROM point_ledger)"
            + "+ (SELECT COUNT(*) FROM member_coupon) + (SELECT COUNT(*) FROM coupon_usage_history) "
            + "+ (SELECT COUNT(*) FROM event_log) + (SELECT COUNT(*) FROM campaign_daily_report)"
            + "+ (SELECT COUNT(*) FROM store_business_hour) + (SELECT COUNT(*) FROM store_campaign_summary)"
            + "+ (SELECT COUNT(*) FROM store_image)";

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
    void 시드_가게는_공통_대표_이미지와_상세_조회에_필요한_시간과_당일_할인을_갖는다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM store_business_hour WHERE store_id IN (9001, 9002, 9003)"))
                .isEqualTo(21);
        assertThat(count("SELECT COUNT(*) FROM store_image")).isEqualTo(116);
        assertThat(count("""
                SELECT COUNT(DISTINCT store_id) FROM store_image
                WHERE image_type = 'LOGO' AND upload_status = 'VERIFIED'
                  AND object_key = 'stores/9222/logo/afecbe89-4e07-424a-ba4f-ad9baa9c8bf6.jpg'
                  AND sort_order IS NULL AND verified_at IS NOT NULL
                """)).isEqualTo(116);
        assertThat(count("SELECT COUNT(*) FROM store_menu_image")).isZero();
        assertThat(count("SELECT COUNT(*) FROM store_campaign_summary WHERE business_date = '2026-10-15'"))
                .isEqualTo(113);
        assertThat(count("SELECT COUNT(*) FROM store_campaign_summary WHERE campaign_id = 9001 "
                + "AND discount_target_type = 'ALL' AND discount_value = 2000")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM store_campaign_summary WHERE campaign_id = 9002 "
                + "AND discount_target_type = 'MENU' AND target_menu_id = 9004")).isEqualTo(1);
        long hours = count("SELECT COUNT(*) FROM store_business_hour");

        migrate(true, "2026-10-16");

        assertThat(count("SELECT COUNT(*) FROM store_image")).isEqualTo(116);
        assertThat(count("SELECT COUNT(*) FROM store_business_hour")).isEqualTo(hours);
        assertThat(count("SELECT COUNT(*) FROM store_campaign_summary WHERE business_date = '2026-10-16'"))
                .isEqualTo(113);
        assertThat(count("SELECT COUNT(*) FROM store_campaign_summary WHERE business_date = '2026-10-15'"))
                .isEqualTo(113);
    }

    @Test

    void R20을_다시_실행해도_기존_대표_이미지를_유지하고_이미지_행이_늘지_않는다() throws Exception {
        migrate(true, "2026-10-15");
        jdbc.update("UPDATE store_image SET object_key = 'stores/9001/logo/custom.jpg' "
                + "WHERE store_id = 9001 AND image_type = 'LOGO'");
        String sql = new ClassPathResource("db/seed/R__20_owner_store.sql")
                .getContentAsString(StandardCharsets.UTF_8).replace("${seedPasswordHash}", HASH);

        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(dataSource);

        assertThat(count("SELECT COUNT(*) FROM store_image")).isEqualTo(116);
        assertThat(jdbc.queryForObject("SELECT object_key FROM store_image "
                + "WHERE store_id = 9001 AND image_type = 'LOGO'", String.class))
                .isEqualTo("stores/9001/logo/custom.jpg");
    }

    @Test
    void 결제_테스트_점주_10명은_이력_없이_생성되고_재실행해도_기존_정보를_유지한다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id BETWEEN 9019 AND 9028 "
                + "AND email = CONCAT('owner', owner_id - 9000, '@seed.lunchcatch.com') "
                + "AND role = 'OWNER' AND status = 'ACTIVE' AND tutorial_viewed = 1")).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT password_hash FROM owner WHERE owner_id BETWEEN 9019 AND 9028", String.class))
                .hasSize(10).containsOnly(HASH);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE owner_id BETWEEN 9019 AND 9028")).isZero();
        assertThat(count("SELECT COUNT(*) FROM point_ledger WHERE owner_id BETWEEN 9019 AND 9028")).isZero();
        assertThat(count("SELECT COUNT(*) FROM refund_request WHERE owner_id BETWEEN 9019 AND 9028")).isZero();
        jdbc.update("UPDATE owner SET password_hash = ?, status = 'SUSPENDED', suspended_at = NOW(6) WHERE owner_id = 9019", HASH.replace("$2a$", "$2b$"));
        jdbc.update("DELETE FROM flyway_history_seed WHERE script = 'R__22_payment_test_owners.sql'");

        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id BETWEEN 9019 AND 9028")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM owner WHERE owner_id = 9019", String.class))
                .isEqualTo(HASH.replace("$2a$", "$2b$"));
        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id = 9019 AND status = 'SUSPENDED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payment WHERE owner_id BETWEEN 9019 AND 9028")).isZero();
    }

    @Test
    void 시드를_켜면_계정과_가게와_오늘의_피드_행이_들어간다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM admin WHERE login_id = 'superadmin' AND role = 'SUPER_ADMIN'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM owner WHERE owner_id IN (9001, 9002) AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM store WHERE store_id IN (9001, 9002) AND finalized = 1")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id IN (9001, 9002) AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM member_profile WHERE onboarding_completed_at IS NOT NULL")).isEqualTo(50);
        /*
         * 피드 시험용 9003~9016 과 9101~9200 이 더 있다. 9108~9110 은 일시 중단이라 서빙 가능한 후보가 셋 적다.
         * 9016 과 9105~9107 은 예산 계획이 없는 후보라 예산은 넷 적다.
         */
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 9001 AND 9016 AND status = 'ACTIVE'")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM poster WHERE campaign_id BETWEEN 9001 AND 9016")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-15' AND campaign_state = 'SERVABLE'")).isEqualTo(113);
        assertThat(count("SELECT COUNT(*) FROM budget_daily_plan WHERE business_date = '2026-10-15'")).isEqualTo(112);
        // 9101~9200 가운데 서빙되는 94개의 쿠폰 재고가 더해진다. 16개는 수량 3, 78개는 수량 50 이다
        assertThat(count("SELECT COUNT(*) FROM coupon_inventory")).isEqualTo(344 + 16 * 3 + 78 * 50);
        assertThat(count("SELECT COUNT(*) FROM coupon_event WHERE campaign_id IN (9017, 9018) "
                + "AND business_date BETWEEN '2026-10-01' AND '2026-10-15'")).isEqualTo(30);
        assertThat(count("SELECT COUNT(DISTINCT business_date) FROM coupon_event "
                + "WHERE campaign_id IN (9017, 9018)")).isEqualTo(15);
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

    void 광고_서빙의_모든_테이블에_서로_맞물린_행이_들어간다() {
        migrate(true, "2026-10-15");

        // 오늘과 지난 6일의 후보다
        assertThat(count("SELECT COUNT(*) FROM ad_candidate")).isEqualTo(112);
        // 회원 9001~9010 이 지난 영업일에 피드를 한 번씩 받았다
        assertThat(count("SELECT COUNT(*) FROM serve_log")).isEqualTo(100);
        assertThat(count("SELECT COUNT(DISTINCT feed_id) FROM serve_log")).isEqualTo(10);
        // 정산 시드(#442)가 같은 serve_id 를 만들 수 있게 규칙으로 정했다
        assertThat(count("SELECT COUNT(*) FROM serve_log WHERE business_date = DATE_SUB('2026-10-15', INTERVAL MOD(member_id, 6) + 1 DAY) "
                + "AND serve_id = UNHEX(MD5(CONCAT('seed-serve-', member_id, '-', business_date, '-', slot_position)))")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM impression_log i JOIN serve_log s USING (serve_id, member_id, campaign_id)")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM impression_log WHERE validity = 'VALID' AND invalid_reason IS NULL")).isEqualTo(90);
        assertThat(count("SELECT COUNT(*) FROM swipe_log w JOIN serve_log s USING (serve_id, member_id, campaign_id)")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM swipe_log WHERE action = 'WISH'")).isEqualTo(28);
        // 피드의 찜은 출처 카드가 있다. 스와이프의 찜마다 찜 목록에 하나다
        assertThat(count("SELECT COUNT(*) FROM wishlist w JOIN swipe_log s ON s.serve_id = w.serve_id AND s.member_id = w.member_id "
                + "AND s.action = 'WISH'")).isEqualTo(28);
        assertThat(count("SELECT COUNT(*) FROM wishlist WHERE serve_id IS NULL")).isEqualTo(81);
        // 3의 배수 회원은 빈 찜 목록을 보려고 찜을 받지 않는다
        assertThat(count("SELECT COUNT(*) FROM wishlist WHERE MOD(member_id, 3) = 0")).isZero();
        assertThat(count("SELECT COUNT(*) FROM feed_visit_daily")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM feed_filter")).isEqualTo(30);
        assertThat(count("SELECT COUNT(*) FROM feed_filter_category")).isEqualTo(90);
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
        // 광고 서빙 시드의 노출과 직접 맞춘다. 유효 노출마다 판정과 차감이 하나씩, 무효 노출에는 없다
        assertThat(count("SELECT COUNT(*) FROM impression_log i JOIN deduction_result r ON r.serve_id = i.serve_id "
                + "AND r.campaign_id = i.campaign_id AND r.business_date = i.business_date WHERE i.validity = 'VALID'"))
                .isEqualTo(90);
        assertThat(count("SELECT COUNT(*) FROM impression_log i JOIN deduction_result r ON r.serve_id = i.serve_id "
                + "WHERE i.validity = 'INVALID'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM impression_log i JOIN point_ledger l ON l.serve_id = i.serve_id "
                + "AND l.entry_type = 'DEDUCT' WHERE i.validity = 'INVALID'")).isZero();
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

    /*
     * 스케줄러가 꺼진 로컬은 시드의 그날 후보로 피드를 본다. 적재가 도는 서버에서는 적재가 같은 키를 덮어써서 두 값이 같아야 한다(#582).
     * 적재가 캠페인과 가게에서 옮기는 열을 시드의 캠페인, 가게, 메뉴 행과 맞댄다. 등록 시각과 상태 전이 시각은 시드가 NOW 로 넣어 견주지 않는다.
     */
    @Test
    void 시드의_그날_후보는_시드의_캠페인과_가게에서_적재가_만드는_값과_같다() {
        migrate(true, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-15'")).isPositive();
        assertThat(count("""
                SELECT COUNT(*) FROM ad_candidate c
                  JOIN campaign p ON p.campaign_id = c.campaign_id
                  JOIN store s ON s.store_id = c.store_id
                  LEFT JOIN store_menu m ON m.store_menu_id = p.target_menu_id
                 WHERE c.business_date = '2026-10-15'
                   AND NOT (c.store_id = p.store_id AND c.store_name = s.name AND c.category_code = s.category_code
                        AND c.latitude = s.latitude AND c.longitude = s.longitude
                        AND c.target_radius = p.target_radius AND c.target_gender = p.target_gender
                        AND c.target_age_groups = p.target_age_groups AND c.discount_target_type = p.discount_target_type
                        AND c.discount_type = p.discount_type AND c.discount_value = p.discount_value
                        AND c.discount_menu_name <=> m.name AND c.usable_start_time = p.usable_start_time
                        AND c.usable_end_time = p.usable_end_time AND c.issue_quantity = p.issue_quantity)
                """)).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM ad_candidate c LEFT JOIN campaign p ON p.campaign_id = c.campaign_id
                 WHERE c.business_date = '2026-10-15' AND p.campaign_id IS NULL
                """)).isZero();
    }

    /*
     * 강남역에 선 회원이 그날 받을 수 있는 후보를 세어 피드 시험 캠페인 9101~9200 의 배치를 본다(#611).
     * 피드처럼 서빙 가능한 상태, 예산 계획, 노출 반경, 노출 대상 성별과 연령대로 거른다. 회원 성별 OTHER 는 전체 대상만 받는다.
     */
    private long visible(String gender, String ageGroup, String extra) {
        return count("SELECT COUNT(*) FROM ad_candidate c "
                + "JOIN budget_daily_plan b ON b.campaign_id = c.campaign_id AND b.business_date = c.business_date "
                + "WHERE c.business_date = '2026-10-15' AND c.campaign_state = 'SERVABLE' "
                + "AND ST_Distance_Sphere(POINT(c.longitude, c.latitude), POINT(127.0276, 37.4979)) <= c.target_radius "
                + "AND (c.target_gender = 'ALL' OR c.target_gender = '" + gender + "') "
                + "AND FIND_IN_SET('" + ageGroup + "', c.target_age_groups) > 0 " + extra);
    }

    @Test
    void 강남역_회원은_성별과_연령대가_달라도_50개_넘게_받고_업종마다_5개_이상_받는다() {
        migrate(true, "2026-10-15");

        for (String gender : new String[]{"MALE", "FEMALE", "OTHER"}) {
            for (String age : new String[]{"AGE_20S", "AGE_30S", "AGE_40S", "AGE_50_PLUS"}) {
                assertThat(visible(gender, age, "")).as(gender + " " + age).isGreaterThan(50);
                // 9014, 9015 를 함께 받는 20대 남성만 1 km 안이 10개라 반경이 1000 에 남는다
                assertThat(visible(gender, age, "AND ST_Distance_Sphere(POINT(c.longitude, c.latitude), "
                        + "POINT(127.0276, 37.4979)) <= 1000")).as(gender + " " + age).isLessThanOrEqualTo(10);
                for (String category : new String[]{"KOREAN", "CHINESE", "JAPANESE", "WESTERN", "BUNSIK", "ASIAN",
                        "FAST_FOOD", "CAFE_DESSERT", "OTHER"}) {
                    assertThat(visible(gender, age, "AND c.category_code = '" + category + "'"))
                            .as(gender + " " + age + " " + category).isGreaterThanOrEqualTo(5);
                }
            }
        }
        // 빠지는 경우 9101~9110 은 누구에게도 보이지 않는다
        assertThat(visible("MALE", "AGE_30S", "AND c.campaign_id BETWEEN 9101 AND 9110")).isZero();
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 9101 AND 9200 AND target_gender = 'ALL'"))
                .isEqualTo(60);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 9101 AND 9200 AND target_gender = 'MALE'"))
                .isEqualTo(20);
        assertThat(count("SELECT COUNT(*) FROM campaign WHERE campaign_id BETWEEN 9101 AND 9200 AND target_gender = 'FEMALE'"))
                .isEqualTo(20);
    }

    @Test
    void 같은_날_다시_돌면_행이_늘지_않는다() {
        migrate(true, "2026-10-15");
        long before = count(ROW_COUNTS);

        migrate(true, "2026-10-15");

        assertThat(count(ROW_COUNTS)).isEqualTo(before);
    }

    @Test
    void 날짜가_바뀌면_그날의_행만_새로_생긴다() {
        migrate(true, "2026-10-15");

        migrate(true, "2026-10-16");

        assertThat(count("SELECT COUNT(*) FROM ad_candidate WHERE business_date = '2026-10-16'")).isEqualTo(116);
        // 9101~9200 가운데 서빙되는 94개는 날마다 쿠폰 이벤트를 받는다
        assertThat(count("SELECT COUNT(*) FROM coupon_event")).isEqualTo(38 + 2 * 94);
        assertThat(count("SELECT COUNT(*) FROM owner")).isEqualTo(128);
    }

    // 카카오로 가입한 회원은 시드보다 늦게 생긴다. 날짜 시드가 다음 날 다시 돌 때 홀수 회원만 찜 셋을 받는다
    @Test
    void 그사이_온보딩한_카카오_회원은_다음_날_홀수_회원만_찜을_받는다() {
        migrate(true, "2026-10-15");
        for (long id : new long[]{1, 2}) {
            jdbc.update("INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at, location_opt_in) "
                    + "VALUES (?, ?, '가입회원', 'ACTIVE', NOW(6), NOW(6), 1)", id, "kakao-" + id);
            jdbc.update("INSERT INTO member_profile (member_id, gender, age_group, latitude, longitude, onboarding_completed_at, "
                    + "created_at, updated_at) VALUES (?, 'FEMALE', 'AGE_20S', 37.4979, 127.0276, NOW(6), NOW(6), NOW(6))", id);
        }

        migrate(true, "2026-10-16");

        assertThat(count("SELECT COUNT(*) FROM wishlist WHERE member_id = 1 AND campaign_id IN (9001, 9002, 9010)")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM wishlist WHERE member_id = 2")).isZero();
    }

    @Test
    void 시드를_끄면_시드_이력도_행도_없다() {
        migrate(false, "2026-10-15");

        assertThat(count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'flyway_history_seed'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM owner")).isZero();
    }
}
