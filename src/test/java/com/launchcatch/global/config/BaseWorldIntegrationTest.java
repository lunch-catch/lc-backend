package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.global.config.DomainFlywayMigrator.BaseSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
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


    static final String[][] CONSISTENCY = {
            {"캠페인의 가게가 있다", "SELECT COUNT(*) FROM campaign c LEFT JOIN store s ON s.store_id = c.store_id WHERE c.campaign_id >= 10000000 AND s.store_id IS NULL"},
            {"캠페인의 점주가 가게 점주와 같다", "SELECT COUNT(*) FROM campaign c JOIN store s ON s.store_id = c.store_id WHERE c.campaign_id >= 10000000 AND c.owner_id <> s.owner_id"},
            {"캠페인 점주는 ACTIVE 다", "SELECT COUNT(*) FROM campaign c JOIN owner o ON o.owner_id = c.owner_id WHERE c.campaign_id >= 10000000 AND o.status <> 'ACTIVE'"},
            {"MENU 캠페인의 메뉴가 그 가게 메뉴다", "SELECT COUNT(*) FROM campaign c LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id AND m.store_id = c.store_id WHERE c.discount_target_type = 'MENU' AND m.store_menu_id IS NULL"},
            {"쿠폰 이벤트의 가게 이름이 가게와 같다", "SELECT COUNT(*) FROM coupon_event e JOIN store s ON s.store_id = e.store_id WHERE e.store_name <> s.name"},
            {"쿠폰 이벤트의 캠페인과 가게가 맞다", "SELECT COUNT(*) FROM coupon_event e LEFT JOIN campaign c ON c.campaign_id = e.campaign_id AND c.store_id = e.store_id WHERE c.campaign_id IS NULL"},
            {"쿠폰 이벤트 할인이 캠페인과 같다", "SELECT COUNT(*) FROM coupon_event e JOIN campaign c ON c.campaign_id = e.campaign_id WHERE e.discount_target_type <> c.discount_target_type OR e.discount_type <> c.discount_type OR e.discount_value <> c.discount_value OR e.issue_quantity <> c.issue_quantity"},
            {"쿠폰 이벤트 메뉴 이름과 가격이 메뉴와 같다", "SELECT COUNT(*) FROM coupon_event e JOIN store_menu m ON m.store_menu_id = e.target_menu_id WHERE e.target_menu_name <> m.name OR e.target_menu_price <> m.price"},
            {"후보의 가게와 좌표가 가게와 같다", "SELECT COUNT(*) FROM ad_candidate a JOIN store s ON s.store_id = a.store_id WHERE ABS(a.latitude - s.latitude) > 0.0000001 OR ABS(a.longitude - s.longitude) > 0.0000001 OR a.store_name <> s.name"},
            {"후보의 캠페인과 가게가 맞다", "SELECT COUNT(*) FROM ad_candidate a LEFT JOIN campaign c ON c.campaign_id = a.campaign_id AND c.store_id = a.store_id WHERE c.campaign_id IS NULL"},
            {"서빙 기록의 캠페인과 가게가 맞고 회원이 있다", "SELECT COUNT(*) FROM serve_log v LEFT JOIN campaign c ON c.campaign_id = v.campaign_id AND c.store_id = v.store_id LEFT JOIN member m ON m.member_id = v.member_id WHERE c.campaign_id IS NULL OR m.member_id IS NULL"},
            {"서빙 날짜가 캠페인 기간 안이다", "SELECT COUNT(*) FROM serve_log v JOIN campaign c ON c.campaign_id = v.campaign_id WHERE v.business_date < c.start_date OR v.business_date > c.end_date"},
            {"찜의 캠페인이 있다", "SELECT COUNT(*) FROM wishlist w LEFT JOIN campaign c ON c.campaign_id = w.campaign_id WHERE c.campaign_id IS NULL"},
            {"요약의 캠페인 상태와 할인이 캠페인과 같다", "SELECT COUNT(*) FROM store_campaign_summary x JOIN campaign c ON c.campaign_id = x.campaign_id WHERE x.store_id <> c.store_id OR x.discount_type <> c.discount_type OR x.discount_value <> c.discount_value"},
            {"예산 계획의 점주가 캠페인 점주와 같다", "SELECT COUNT(*) FROM budget_daily_plan b JOIN campaign c ON c.campaign_id = b.campaign_id WHERE b.owner_id <> c.owner_id"},
            {"원장의 캠페인 점주가 맞다", "SELECT COUNT(*) FROM point_ledger l JOIN campaign c ON c.campaign_id = l.campaign_id WHERE l.owner_id <> c.owner_id"},
            {"잔액이 음수인 점주가 없다", "SELECT COUNT(*) FROM (SELECT owner_id, SUM(amount) b FROM point_ledger WHERE entry_type <> 'DEDUCT' GROUP BY owner_id HAVING b < 0) x"},
            {"포인트 계정 없는 원장 점주가 없다", "SELECT COUNT(DISTINCT l.owner_id) FROM point_ledger l LEFT JOIN point_account a ON a.owner_id = l.owner_id WHERE a.owner_id IS NULL"},
            {"발급 쿠폰의 회원이 있다", "SELECT COUNT(*) FROM member_coupon mc LEFT JOIN member m ON m.member_id = mc.member_id WHERE m.member_id IS NULL"},
            {"최근 7일 리포트의 유효 노출이 노출 기록과 같다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN (SELECT campaign_id, business_date, COUNT(*) n FROM impression_log WHERE validity = 'VALID' GROUP BY campaign_id, business_date) i ON i.campaign_id = r.campaign_id AND i.business_date = r.business_date WHERE r.business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 7 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY) AND r.valid_impression_count <> COALESCE(i.n, 0)"},
            {"최근 7일 리포트의 차감액이 차감 원장과 같다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN (SELECT campaign_id, DATE(created_at) d, -SUM(amount) n FROM point_ledger WHERE entry_type = 'DEDUCT' GROUP BY campaign_id, DATE(created_at)) l ON l.campaign_id = r.campaign_id AND l.d = r.business_date WHERE r.business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 7 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY) AND r.charged_amount <> COALESCE(l.n, 0)"},
            {"리포트의 유효 노출은 과금과 미과금의 합이다", "SELECT COUNT(*) FROM campaign_daily_report WHERE valid_impression_count <> billable_impression_count + not_billed_impression_count"},
            {"리포트 캠페인이 있다", "SELECT COUNT(*) FROM campaign_daily_report r LEFT JOIN campaign c ON c.campaign_id = r.campaign_id WHERE c.campaign_id IS NULL"},
            {"분석 날짜마다 집계 SUCCESS 가 있다", "SELECT 60 - COUNT(DISTINCT business_date) FROM batch_execution_log WHERE job_name = 'DAILY_AGGREGATION' AND status = 'SUCCESS' AND finished_at IS NOT NULL AND business_date BETWEEN DATE_SUB('2026-10-28', INTERVAL 60 DAY) AND DATE_SUB('2026-10-28', INTERVAL 1 DAY)"},
            {"RUNNING 배치가 없다", "SELECT COUNT(*) FROM batch_execution_log WHERE status = 'RUNNING'"}
    };

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

    /*
     * 0 이 아닌 일관성 검사를 "이름=어긋난 행 수" 로 모은다.
     * 기본 세계를 넣은 뒤 검사하는 테스트가 같이 쓴다.
     */
    static List<String> consistencyViolations(JdbcTemplate jdbc) {
        return Arrays.stream(CONSISTENCY)
                .map(check -> check[0] + "=" + jdbc.queryForObject(check[1], Long.class))
                .filter(result -> !result.endsWith("=0"))
                .toList();
    }

    // 0 ~ 14 일 (기준일부터 2주)
    private static final String FOURTEEN_DAYS = "SELECT a.d + b.d * 5 AS day_offset FROM "
            + "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) a CROSS JOIN "
            + "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2) b";

    // 행마다 가장 가까운 역(0 강남역, 1 역삼역, 2 선릉역)과 그 역까지의 거리(m)를 붙인다
    private static String nearestStation(String lat, String lng, String table) {
        return nearestStation(lat, lng, "", table);
    }

    // columns 는 역과 거리 앞에 함께 고를 열이다 (끝에 쉼표)
    private static String nearestStation(String lat, String lng, String columns, String table) {
        String d0 = distance(lat, lng, 37.4979, 127.0276);
        String d1 = distance(lat, lng, 37.5006, 127.0364);
        String d2 = distance(lat, lng, 37.5045, 127.0490);
        return "SELECT " + columns + "CASE WHEN " + d0 + " <= LEAST(" + d1 + ", " + d2 + ") THEN 0 WHEN " + d1 + " <= " + d2 + " THEN 1 ELSE 2 END AS nearest, "
                + "LEAST(" + d0 + ", " + d1 + ", " + d2 + ") AS dist FROM " + table;
    }

    private static String distance(String lat, String lng, double stationLat, double stationLng) {
        return "SQRT(POW((" + lat + " - " + stationLat + ") * 111000, 2) + POW((" + lng + " - " + stationLng + ") * 88200, 2))";
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

        /*
         * 파일끼리 같은 공식으로 계산한 값이 서로 맞는지 본다.
         * 어긋난 행 수를 모두 남기고 한 번에 판정한다.
         */
        assertThat(consistencyViolations(jdbc)).isEmpty();

        // 쓰지 않게 된 시간대별 목표 표는 시드 V8 이 지운다(#829)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = 'budget_hourly_target'", Long.class)).isZero();

        // 기본 세계 환불 요청 8건의 수수료는 상태와 상관없이 0 이다(#896)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request "
                + "WHERE refund_request_id BETWEEN 10000001 AND 10000008 AND fee = 0", Long.class)).isEqualTo(8L);

        // 기본 세계 가게(1,000만 대역)는 가장 가까운 역에서 500 m 안이 역마다 100곳이고, 오늘 피드 후보는 역마다 70개다 (#858)
        assertThat(jdbc.queryForList("SELECT nearest, COUNT(*) FROM (" + nearestStation("latitude", "longitude", "store")
                + " WHERE store_id BETWEEN 10000001 AND 19999999 AND latitude IS NOT NULL) x WHERE dist <= 500 GROUP BY nearest ORDER BY nearest"))
                .extracting(row -> ((Number) row.get("COUNT(*)")).longValue())
                .containsExactly(100L, 100L, 100L);
        assertThat(jdbc.queryForList("SELECT nearest, COUNT(*) FROM (" + nearestStation("latitude", "longitude", "ad_candidate")
                + " WHERE business_date = '2026-10-28' AND campaign_id BETWEEN 10000001 AND 19999999) x WHERE dist <= 500 GROUP BY nearest ORDER BY nearest"))
                .extracting(row -> ((Number) row.get("COUNT(*)")).longValue())
                .containsExactly(70L, 70L, 70L);
        /*
         * 기준일부터 14일 동안 날마다 역별 진행 중 캠페인이 70개다.
         * 피드 후보와 쿠폰은 00:00 배치가 이 캠페인으로 만든다.
         */
        assertThat(jdbc.queryForList("SELECT day_offset, nearest, COUNT(*) cnt FROM (" + nearestStation("s.latitude", "s.longitude",
                "dd.day_offset, ", "campaign c JOIN store s ON s.store_id = c.store_id JOIN (" + FOURTEEN_DAYS + ") dd "
                + "ON DATE_ADD('2026-10-28', INTERVAL dd.day_offset DAY) BETWEEN c.start_date AND c.end_date")
                + " WHERE c.campaign_id BETWEEN 10000001 AND 19999999 AND c.status = 'ACTIVE') x "
                + "WHERE dist <= 500 GROUP BY day_offset, nearest"))
                .hasSize(45)
                .allSatisfy(row -> assertThat(((Number) row.get("cnt")).longValue()).as(row.toString()).isEqualTo(70L));
        // 모든 가게가 강남구 안이다 (위도 37.46 ~ 37.535, 경도 127.01 ~ 127.125)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store WHERE latitude IS NOT NULL AND (latitude NOT BETWEEN 37.46 AND 37.535 "
                + "OR longitude NOT BETWEEN 127.01 AND 127.125)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM owner WHERE owner_id BETWEEN 10000001 AND 19999999", Long.class))
                .isEqualTo(500L);

        // 메뉴 할인 캠페인에는 정률과 정액이 둘 다 있어야 한다
        assertThat(jdbc.queryForList("SELECT DISTINCT discount_type FROM campaign "
                + "WHERE campaign_id >= 10000000 AND discount_target_type = 'MENU'", String.class))
                .containsExactlyInAnyOrder("PERCENT", "AMOUNT");

        // 선착순 쿠폰 전단지 10종은 게시되고 활성이며 앱의 템플릿 규칙(허용 태그, 슬롯 넷)을 지킨다 (#886)
        assertThat(jdbc.queryForList("SELECT html_content FROM template WHERE template_id BETWEEN 10000001 AND 10000010 "
                + "AND status = 'PUBLISHED' AND active = 1", String.class))
                .hasSize(10)
                .allSatisfy(html -> {
                    assertThat(new TemplateHtmlSanitizer().sanitize(html).removedElements()).isEmpty();
                    new TemplateSlotValidator().validate(html);
                    // 생성기 지침: 슬롯 넷 말고 글자를 넣지 않고, 글꼴은 sans-serif 나 serif 기본값만 쓴다
                    assertThat(html.replaceAll("<[^>]+>", "").replace("이벤트명", "").replace("할인 내용", "").replace("기간", ""))
                            .isBlank();
                    assertThat(html).doesNotContainPattern("font-family:(?!sans-serif|serif)");
                });
        // 포스터는 가게 업종의 스타일과 할인 대상(전체할인 홀수, 메뉴할인 짝수)으로 템플릿을 고른다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster p JOIN campaign c ON c.campaign_id = p.campaign_id "
                + "JOIN store s ON s.store_id = c.store_id WHERE p.campaign_id BETWEEN 10000001 AND 29999999 AND p.template_id <> 10000000 "
                + "+ CASE s.category_code WHEN 'KOREAN' THEN 0 WHEN 'BUNSIK' THEN 0 WHEN 'CHINESE' THEN 2 WHEN 'ASIAN' THEN 2 "
                + "WHEN 'JAPANESE' THEN 4 WHEN 'WESTERN' THEN 6 WHEN 'CAFE_DESSERT' THEN 6 ELSE 8 END "
                + "+ IF(c.discount_target_type = 'MENU', 2, 1)", Long.class)).isZero();
        // 기본 세계 가게와 메뉴는 "중식 001호점", "중식 메뉴 1" 같은 자리 이름이 아니라 실제 이름처럼 보이고 가게 이름은 겹치지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store WHERE store_id BETWEEN 10000001 AND 10000500 "
                + "AND name LIKE '%호점'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) - COUNT(DISTINCT name) FROM store WHERE store_id BETWEEN 10000001 AND 10000500",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store_menu WHERE store_id BETWEEN 10000001 AND 10000500 "
                + "AND name LIKE '% 메뉴 _'", Long.class)).isZero();
        // 쿠폰 회차와 피드 후보에 복사된 가게 이름이 가게와 같다
        assertThat(jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM coupon_event e JOIN store s ON s.store_id = e.store_id "
                + "WHERE e.store_name <> s.name) + (SELECT COUNT(*) FROM ad_candidate a JOIN store s ON s.store_id = a.store_id "
                + "WHERE a.store_name <> s.name)", Long.class)).isZero();
        /*
         * 포스터의 image 칸은 메뉴 사진이고, 메뉴가 없는 가게는 로고다.
         * 사용 시간은 "오늘" 로 시작한다.
         */
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster WHERE campaign_id BETWEEN 10000001 AND 29999999 "
                + "AND ((JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.image.value')) NOT LIKE '%/menu-%.jpg' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.image.value')) NOT LIKE '%/logo%.jpg' "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.image.value')) NOT LIKE '%/poster.jpg') "
                + "OR JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.period.value')) NOT LIKE '오늘 %')", Long.class)).isZero();
        // 팀원이 개발에서 만든 템플릿 19개와 버전 128개가 들어가고, 없는 관리자는 superadmin 으로 바뀐다 (#930)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template WHERE template_id NOT BETWEEN 10000001 AND 10000014", Long.class))
                .isEqualTo(19L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_version WHERE template_id NOT BETWEEN 10000001 AND 10000014",
                Long.class)).isEqualTo(128L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template t LEFT JOIN admin a ON a.admin_id = t.last_modified_by "
                + "WHERE t.last_modified_by IS NOT NULL AND a.admin_id IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT current_count FROM template_quota", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM template", Long.class));
        // 피드 가게(진행 중 캠페인 1 ~ 180, 301 ~ 330 의 가게)는 모두 메뉴와 사진이 있어 포스터 이미지 칸이 로고가 아니다 (#941)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store s WHERE (s.store_id BETWEEN 10000001 AND 10000180 "
                + "OR s.store_id BETWEEN 10000301 AND 10000330) AND NOT EXISTS (SELECT 1 FROM store_menu m JOIN store_menu_image i "
                + "ON i.store_menu_id = m.store_menu_id WHERE m.store_id = s.store_id)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster WHERE (campaign_id BETWEEN 10000001 AND 10000180 "
                + "OR campaign_id BETWEEN 10000301 AND 10000330) "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.image.value')) NOT LIKE '%/menu-%.jpg'", Long.class)).isZero();
        // 메뉴 0개 확정 가게 시나리오는 피드 밖 가게에 남는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM store s WHERE s.store_id BETWEEN 10000001 AND 10000500 AND s.finalized = 1 "
                + "AND NOT EXISTS (SELECT 1 FROM store_menu m WHERE m.store_id = s.store_id)", Long.class)).isPositive();
        // 이미지 칸이 로고를 쓰면 그 가게에 실제로 있는 로고다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster p WHERE p.campaign_id BETWEEN 10000001 AND 19999999 "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(p.slot_values, '$.image.value')) LIKE '%/logo%' AND NOT EXISTS (SELECT 1 FROM store_image i "
                + "WHERE i.image_type = 'LOGO' AND JSON_UNQUOTE(JSON_EXTRACT(p.slot_values, '$.image.value')) LIKE CONCAT('%/', i.object_key))",
                Long.class)).isZero();
        // 할인 금액은 천 단위 쉼표를 찍는다 (2,000원)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poster WHERE campaign_id BETWEEN 10000001 AND 29999999 "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(slot_values, '$.discount.value')) REGEXP '[0-9]{4}원'", Long.class)).isZero();
    }

    /*
     * 운영처럼 팀원 템플릿 번호(20000002)를 다른 템플릿과 버전이 이미 쓰는 DB 다 (#930).
     * 그 행은 그대로 두고 팀원 템플릿과 버전은 번호에 100,000 을 더해 들어간다.
     */
    @Test
    void 팀원_템플릿은_번호가_겹치면_옮겨_들어간다() {
        new DomainFlywayMigrator(dataSource).afterPropertiesSet();
        jdbc.update("INSERT INTO template (template_id, name, status, active, created_at, updated_at) "
                + "VALUES (20000002, '운영에서 만든 템플릿', 'DRAFT', 0, '2026-10-01 10:00:00', '2026-10-01 10:00:00')");
        jdbc.update("INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id, "
                + "html_content, message, created_at, updated_at) VALUES (20000002, 20000002, 1, 'p', 'prod-1', '<div></div>', 'm', "
                + "'2026-10-01 10:00:00', '2026-10-01 10:00:00')");
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", HASH,
                "demoAdminPasswordHash", HASH, "demoOwnerPasswordHash", HASH, "scenarioPasswordHash", HASH,
                "baseCdnBaseUrl", "https://cdn.example.com");
        new DomainFlywayMigrator(dataSource, new BaseSettings(true, placeholders)).afterPropertiesSet();

        assertThat(jdbc.queryForObject("SELECT name FROM template WHERE template_id = 20000002", String.class))
                .isEqualTo("운영에서 만든 템플릿");
        assertThat(jdbc.queryForObject("SELECT name FROM template WHERE template_id = 20100002", String.class))
                .isEqualTo("새 템플릿1");
        assertThat(jdbc.queryForObject("SELECT template_id FROM template_version WHERE template_version_id = 20100002", Long.class))
                .isNotNull();
        // 옮긴 템플릿의 버전은 옮긴 번호를 가리킨다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM template_version WHERE template_id = 20100002", Long.class))
                .isEqualTo(9L);
    }
}
