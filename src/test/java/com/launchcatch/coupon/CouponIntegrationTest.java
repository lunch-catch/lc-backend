package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.response.CursorPageResponse;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = "coupon.daily-limit=3")
@AutoConfigureMockMvc
class CouponIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final CustomUserDetails MEMBER = new CustomUserDetails(17L, Role.MEMBER);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CouponLookUpService couponLookUpService;
    @Autowired CouponCommandService couponCommandService;
    @MockitoBean Clock clock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("jwt.secret", () -> "test-only-secret-not-used-anywhere-else-0123456789abcdef");
    }

    @BeforeEach
    void setUpClock() {
        when(clock.withZone(ClockConfig.ZONE))
                .thenReturn(Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ClockConfig.ZONE));
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
    }

    @Nested
    @DisplayName("매장 사용 이력 조회")
    @Transactional
    class UsageHistoryQuery {

        private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
        private static final LocalDate TO = LocalDate.of(2026, 9, 30);
        private static final LocalDateTime USE_TIME = TO.atTime(12, 30, 0, 123456000);
        private static final String PATH = "/v1/owner/stores/10/coupon-usages";

        @BeforeEach
        void setUp() {
            insertHistory(5001L, 10L, FROM.atStartOfDay().minusNanos(1000), 1000);
            insertHistory(5002L, 10L, FROM.atStartOfDay(), 2000);
            insertHistory(5003L, 10L, USE_TIME, 2700);
            insertHistory(5004L, 10L, USE_TIME, 3000);
            insertHistory(5005L, 10L, TO.plusDays(1).atStartOfDay(), 4000);
            insertHistory(5006L, 20L, USE_TIME.plusMinutes(1), 5000);
            insertHistory(5007L, 10L, FROM.minusYears(2).atStartOfDay(), 1500);
            entityManager.clear();
        }

        @Test
        void 요청한_매장만_조회하며_시작일_시작은_포함하고_종료일_다음날_시작은_제외한다() {
            var response = couponLookUpService.getUsages(10L, FROM, TO, 20, null);

            assertThat(response.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L);
            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items().get(1).discountAmount()).isEqualTo(2700);
            assertThat(response.items().get(1).useTime()).isEqualTo(USE_TIME.atOffset(ZoneOffset.ofHours(9)));
        }

        @Test
        void 같은_사용_시각의_이력도_페이지에서_누락하지_않으며_새_이력은_기존_커서에_영향을_주지_않는다() {
            var first = couponLookUpService.getUsages(10L, FROM, TO, 1, null);
            insertHistory(5008L, 10L, USE_TIME.plusMinutes(1), 2500);

            var second = couponLookUpService.getUsages(10L, FROM, TO, 1, first.nextPageToken());
            var third = couponLookUpService.getUsages(10L, FROM, TO, 1, second.nextPageToken());

            assertThat(first.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5004L);
            assertThat(second.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5003L);
            assertThat(third.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5002L);
            assertThat(third.nextPageToken()).isNull();
        }

        @Test
        void 같은_날_조회에서는_그날의_사용_이력만_반환한다() {
            var singleDay = couponLookUpService.getUsages(10L, TO, TO, 100, null);

            assertThat(singleDay.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L);
        }

        @Test
        void 조회_기간_길이에_상한_없이_여러_해의_사용_이력을_반환한다() {
            var multipleYears = couponLookUpService.getUsages(10L, FROM.minusYears(3), TO, 100, null);

            assertThat(multipleYears.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L, 5001L, 5007L);
        }

        @Test
        void 이력이_없는_매장은_빈_목록을_반환한다() {
            var response = couponLookUpService.getUsages(30L, FROM, TO, 20, null);

            assertThat(response.items()).isEmpty();
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 점주는_HTTP로_매장_사용_이력을_조회한다() throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(3))
                    .andExpect(jsonPath("$.data.items[0].usageHistoryId").value(5004))
                    .andExpect(jsonPath("$.data.items[1].discountAmount").value(2700))
                    .andExpect(jsonPath("$.data.nextPageToken").isEmpty());
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
        void 점주_외_역할의_HTTP_조회를_차단한다(Role role) throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void 조회는_쿠폰과_사용_이력_및_재고와_발급_횟수를_변경하지_않는다() {
            var coupons = jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id");
            var histories = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id");
            var inventories = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id");
            var limits = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id");

            couponLookUpService.getUsages(10L, FROM, TO, 1, null);

            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id"))
                    .usingRecursiveComparison().isEqualTo(coupons);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id"))
                    .isEqualTo(histories);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id"))
                    .usingRecursiveComparison().isEqualTo(inventories);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id"))
                    .isEqualTo(limits);
        }

        @Test
        void Swagger는_사용_이력의_성공_응답_계약을_설명한다() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/v1/owner/stores/{storeId}/coupon-usages'].get.responses['200'].content['*/*'].example.data.items[0].discountAmount")
                            .value(3000))
                    .andExpect(jsonPath("$.paths['/v1/owner/stores/{storeId}/coupon-usages'].get.responses['200'].content['*/*'].example.data.nextPageToken")
                            .isEmpty());
        }

        @Test
        void 미인증_HTTP_조회는_인증_오류를_반환한다() throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        // 사용 이력 조회에 사용할 이벤트와 사용 완료 쿠폰, 확정 사용 결과를 DB에 저장한다.
        private void insertHistory(Long id, Long storeId, LocalDateTime useTime, int discountAmount) {
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (:id, :id, :businessDate, 100, :startAt, :endAt, 'ACTIVE', :storeId, '런치식당',
                            'ALL', 'AMOUNT', 3000, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("businessDate", useTime.toLocalDate())
                    .setParameter("startAt", useTime.minusHours(1)).setParameter("endAt", useTime.plusHours(1))
                    .setParameter("storeId", storeId).setParameter("useTime", useTime).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version,
                            status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (:id, :id, 17, :code, 0, 'USED', :startAt, :endAt, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("code", UUID.randomUUID())
                    .setParameter("startAt", useTime.minusHours(1)).setParameter("endAt", useTime.plusHours(1))
                    .setParameter("useTime", useTime).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_usage_history (coupon_usage_history_id, member_coupon_id, store_id,
                            discount_amount, created_at, updated_at)
                    VALUES (:id, :id, :storeId, :discountAmount, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("storeId", storeId)
                    .setParameter("discountAmount", discountAmount).setParameter("useTime", useTime).executeUpdate();
        }
    }

    @Nested
    @DisplayName("쿠폰 목록 조회")
    @Transactional
    class ListQuery {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            insertCoupon(1001L, 17L, "EXPIRED", NOW.minusHours(3), NOW.minusHours(2), NOW.minusHours(3));
            insertCoupon(1002L, 17L, "ISSUED", NOW.minusHours(1), NOW, NOW.minusHours(2));
            insertCoupon(1003L, 17L, "ISSUED", NOW, NOW.plusHours(1), NOW.minusHours(1).plusNanos(123456000));
            insertCoupon(1004L, 17L, "ISSUED", NOW.plusHours(1), NOW.plusHours(2), NOW.minusHours(1).plusNanos(123456000));
            insertCoupon(1005L, 17L, "USED", NOW.minusHours(4), NOW.minusHours(3), NOW.minusHours(4));
            insertCoupon(1006L, 17L, "REMOVED", NOW, NOW.plusHours(1), NOW);
            insertCoupon(1007L, 18L, "ISSUED", NOW, NOW.plusHours(1), NOW);
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1005, 10, 3000, :usedAt, :usedAt)
                    """).setParameter("usedAt", NOW.minusHours(4).plusMinutes(30)).executeUpdate();
            entityManager.clear();
        }

        @Test
        void 본인_쿠폰만_발급_시각과_ID_내림차순으로_조회하고_삭제된_쿠폰을_제외한다() {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, null, 20, null);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId)
                    .containsExactly(1004L, 1003L, 1002L, 1001L, 1005L);
            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items().get(2).state().name()).isEqualTo("EXPIRED");
            assertThat(response.items().getLast().discountAmount()).isEqualTo(3000);
        }

        static Stream<Arguments> filters() {
            return Stream.of(
                    Arguments.of("usable = true", List.of(1003L)),
                    Arguments.of("state = \"ISSUED\"", List.of(1004L, 1003L)),
                    Arguments.of("state = \"EXPIRED\"", List.of(1002L, 1001L)),
                    Arguments.of("state = \"USED\"", List.of(1005L)));
        }

        @Test
        void 종료_시각에_도달한_미사용_쿠폰은_목록_조회에서_DB_상태도_만료로_변경한다() {
            couponLookUpService.getCoupons(17L, null, 20, null);
            entityManager.flush();
            entityManager.clear();

            List<?> states = entityManager.createNativeQuery("""
                    SELECT status FROM member_coupon ORDER BY member_coupon_id
                    """).getResultList();

            assertThat(states).isEqualTo(List.of("EXPIRED", "EXPIRED", "ISSUED", "ISSUED", "USED", "REMOVED", "ISSUED"));
        }

        @ParameterizedTest
        @MethodSource("filters")
        void 필터는_조회_시각_상태로_적용한다(String filter, List<Long> expectedIds) {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, filter, 20, null);
            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId)
                    .containsExactlyElementsOf(expectedIds);
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 필터를_먼저_적용하여_사용_시작_전_쿠폰이_많아도_사용_가능_쿠폰의_페이지를_채운다() {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, "usable = true", 1, null);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1003L);
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 발급_시각이_같아도_페이지에서_중복이나_누락이_없고_새_쿠폰은_이전_커서에_영향을_주지_않는다() {
            CursorPageResponse<CouponDetailResponse> first = couponLookUpService.getCoupons(17L, null, 1, null);
            insertCoupon(1008L, 17L, "ISSUED", NOW, NOW.plusHours(1), NOW);

            CursorPageResponse<CouponDetailResponse> second = couponLookUpService.getCoupons(17L, null, 2, first.nextPageToken());
            CursorPageResponse<CouponDetailResponse> third = couponLookUpService.getCoupons(17L, null, 2, second.nextPageToken());

            assertThat(first.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1004L);
            assertThat(second.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1003L, 1002L);
            assertThat(third.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1001L, 1005L);
            assertThat(third.nextPageToken()).isNull();
        }

        @Test
        void 쿠폰_없는_회원에게는_빈_목록을_반환한다() {
            assertThat(couponLookUpService.getCoupons(19L, null, 20, null)).isEqualTo(CursorPageResponse.of(List.of(), null));
        }

        @Test
        void 목록_API는_기본_페이지_크기와_성공_봉투와_한국_시각을_사용한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(5))
                    .andExpect(jsonPath("$.data.items[0].memberCouponId").value(1004))
                    .andExpect(jsonPath("$.data.items[0].usable").value(false))
                    .andExpect(jsonPath("$.data.items[1].usable").value(true))
                    .andExpect(jsonPath("$.data.items[0].issueTime").value("2026-10-10T11:00:00.123456+09:00"))
                    .andExpect(jsonPath("$.data.nextPageToken").isEmpty());
        }

        @Test
        void 인증_없이는_목록을_조회할_수_없다() throws Exception {
            mockMvc.perform(get("/v1/coupons"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
        void 회원_외_역할은_목록_조회를_거절한다(Role role) throws Exception {
            mockMvc.perform(get("/v1/coupons").with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 101})
        void 페이지_크기_범위_오류는_공통_입력_오류로_반환한다(int pageSize) throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageSize", String.valueOf(pageSize))
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 페이지_크기_타입_오류는_공통_해석_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageSize", "abc")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 지원하지_않는_필터는_공통_입력_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("filter", "usable = false")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_단건_쿠폰_ID는_공통_입력_오류로_반환한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id)
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 잘못된_커서는_공통_입력_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageToken", "invalid-token")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/v1/coupons", "/v1/coupons/1005"})
        void 사용_이력이_없는_사용_완료_쿠폰은_목록과_단건에서_동일한_정합성_오류로_반환한다(String path) throws Exception {
            entityManager.createNativeQuery("DELETE FROM coupon_usage_history WHERE member_coupon_id = 1005")
                    .executeUpdate();

            mockMvc.perform(get(path).with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void 필터를_바꾸거나_전체로_전환하면_이전_커서를_거절한다() throws Exception {
            String token = couponLookUpService.getCoupons(17L, "state = \"ISSUED\"", 1, null).nextPageToken();

            mockMvc.perform(get("/v1/coupons").param("pageToken", token).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
            mockMvc.perform(get("/v1/coupons").param("filter", "state = \"USED\"").param("pageToken", token)
                            .with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 반환하지_않은_쿠폰은_만료로_변경하지_않는다() {
            couponLookUpService.getCoupons(17L, null, 1, null);
            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM member_coupon WHERE member_coupon_id = 1002", String.class))
                    .isEqualTo("ISSUED");
        }

        // 목록 조회에 사용할 이벤트 스냅샷과 회원 쿠폰을 DB에 저장한다.
        private void insertCoupon(Long id, Long memberId, String state, LocalDateTime start, LocalDateTime end,
                LocalDateTime issueTime) {
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (:id, :id, :businessDate, 100, :start, :end, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT',
                            3000, :issueTime, :issueTime)
                    """).setParameter("id", id).setParameter("businessDate", NOW.toLocalDate())
                    .setParameter("start", start).setParameter("end", end).setParameter("issueTime", issueTime)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version,
                            status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (:id, :id, :memberId, :code, 0, :state, :start, :end, :issueTime, :issueTime)
                    """).setParameter("id", id).setParameter("memberId", memberId)
                    .setParameter("code", UUID.randomUUID()).setParameter("state", state)
                    .setParameter("start", start).setParameter("end", end).setParameter("issueTime", issueTime)
                    .executeUpdate();
        }
    }

    @Nested
    @DisplayName("쿠폰 통계 조회")
    @Transactional
    class SummaryQuery {

        private static final String PATH = "/v1/coupons/summary";

        @Test
        void 본인의_발급_이력이_없으면_다른_회원의_이력과_관계없이_모두_0이다() throws Exception {
            insertCoupon(1001L, 18L, "USED");
            insertUsage(1001L, 1500);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(0));
        }

        @Test
        void 사용_이력이_없어도_삭제한_쿠폰을_포함한_발급_수를_반환한다() throws Exception {
            insertCoupon(1001L, 17L, "ISSUED");
            insertCoupon(1002L, 17L, "REMOVED");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(0));
        }

        @Test
        void 모든_쿠폰을_발급_수에_포함하고_확정_사용_결과를_변경없이_집계한다() throws Exception {
            insertCoupon(1001L, 17L, "ISSUED");
            insertCoupon(1002L, 17L, "EXPIRED");
            insertCoupon(1003L, 17L, "REMOVED");
            insertCoupon(1004L, 17L, "USED", 0);
            insertUsage(1004L, 0);
            insertCoupon(1005L, 17L, "USED");
            insertUsage(1005L, 1500);
            insertCoupon(1006L, 17L, "USED");
            insertUsage(1006L, 1500);
            insertCoupon(1007L, 18L, "USED");
            insertUsage(1007L, 1500);
            var couponsBefore = jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id");
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(6))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(3))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(3000));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id"))
                    .usingRecursiveComparison()
                    .isEqualTo(couponsBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id"))
                    .isEqualTo(usagesBefore);
        }

        @Test
        void 각_할인액이_int_범위여도_합계는_잘리지_않는다() throws Exception {
            insertCoupon(1001L, 17L, "USED", 3_000_000_000L);
            insertUsage(1001L, 1_500_000_000);
            insertCoupon(1002L, 17L, "USED", 3_000_000_000L);
            insertUsage(1002L, 1_500_000_000);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(3_000_000_000L));
        }

        @Test
        void 인증되지_않은_요청은_로그인이_필요하다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "MEMBER")
        void 회원이_아닌_역할은_조회할_수_없다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        private void insertCoupon(long id, long memberId, String status) {
            insertCoupon(id, memberId, status, 3000);
        }

        // 정률 할인 메뉴의 스냅샷과 회원 쿠폰을 실제 저장 제약에 맞춰 준비한다.
        private void insertCoupon(long id, long memberId, String status, long menuPrice) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event
                        (coupon_event_id, campaign_id, business_date, issue_quantity,
                         usable_start_at, usable_end_at, status, store_id, store_name,
                         discount_target_type, discount_type, discount_value,
                         target_menu_id, target_menu_name, target_menu_price, target_menu_discounted_price,
                         created_at, updated_at)
                    VALUES (?, ?, '2026-10-09', 100, '2026-10-09 11:00:00', '2026-10-09 13:00:00',
                            'ACTIVE', 10, '테스트 매장', 'MENU', 'PERCENT', 50, 1, '테스트 메뉴', ?, ?,
                            '2026-10-09 00:00:00', '2026-10-09 00:00:00')
                    """, id, id, menuPrice, menuPrice / 2);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon
                        (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version, status,
                         usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (?, ?, ?, UNHEX(LPAD(HEX(?), 32, '0')), 0, ?,
                            '2026-10-09 11:00:00', '2026-10-09 13:00:00',
                            '2026-10-09 11:00:00', '2026-10-09 11:00:00')
                    """, id, id, memberId, id, status);
        }

        private void insertUsage(long memberCouponId, int discountAmount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history
                        (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (?, 10, ?, '2026-10-09 12:00:00', '2026-10-09 12:00:00')
                    """, memberCouponId, discountAmount);
        }
    }

    @Nested
    @DisplayName("일일 발급 한도 조회")
    @Transactional
    class IssuanceLimitQuery {

        private static final String PATH = "/v1/coupons/issuance-limit";

        @Test
        void 인증된_회원의_오늘_기록만_조회한다() throws Exception {
            insertDailyLimit(17L, TODAY, 2);
            insertDailyLimit(18L, TODAY, 3);
            insertDailyLimit(17L, TODAY.minusDays(1), 3);
            var before = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.date").value("2026-10-10"))
                    .andExpect(jsonPath("$.data.issuedCount").value(2))
                    .andExpect(jsonPath("$.data.dailyLimit").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id"))
                    .isEqualTo(before);
        }

        @Test
        void 발급_기록이_없으면_0건을_반환하고_기록을_만들지_않는다() throws Exception {
            insertDailyLimit(17L, TODAY.minusDays(1), 3);
            insertDailyLimit(18L, TODAY, 3);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.date").value("2026-10-10"))
                    .andExpect(jsonPath("$.data.issuedCount").value(0))
                    .andExpect(jsonPath("$.data.dailyLimit").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM coupon_daily_limit
                     WHERE member_id = ? AND business_date = ?
                    """, Integer.class, 17L, TODAY))
                    .isZero();
        }

        @Test
        void 인증되지_않은_요청은_로그인이_필요하다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "MEMBER")
        void 회원이_아닌_역할은_조회할_수_없다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        // 발급 횟수는 회원과 영업일별 기록에 저장하며 다른 도메인의 데이터는 필요하지 않다.
        private void insertDailyLimit(long memberId, LocalDate businessDate, int issuedCount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit
                        (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (?, ?, ?, '2026-10-10 12:00:00', '2026-10-10 12:00:00')
                    """, memberId, businessDate, issuedCount);
        }
    }

    @Nested
    @DisplayName("쿠폰 단건 조회")
    class DetailQuery {

        private static final String PATH = "/v1/coupons/1001";
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            setTime(Instant.parse("2026-10-10T03:00:00Z"));
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, store_image_url,
                            discount_target_type, discount_type, discount_value, target_menu_id, target_menu_name,
                            target_menu_price, target_menu_discounted_price, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'https://images.example.org/menu-v1.jpg?size=640&variant=2',
                            'MENU', 'AMOUNT', 3000, 701, '숯불갈비 정식', 12000, 9000, ?, ?)
                    """, TODAY, NOW.minusHours(1), NOW.plusHours(2), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (81, 1, UNHEX('00112233445566778899AABBCCDDEEFF'), 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(2), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at,
                            created_at, updated_at)
                    VALUES (1001, 81, 17, UNHEX('00112233445566778899AABBCCDDEEFF'), 3,
                            UNHEX('FFEEDDCCBBAA99887766554433221100'), ?, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.plusMinutes(1), NOW.minusHours(1), NOW.plusHours(2), NOW.minusMinutes(90), NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, TODAY, NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @Test
        void 본인_쿠폰의_스냅샷과_한국_시각을_반환하고_내부_식별자와_QR은_노출하지_않는다() throws Exception {
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.","data":{
                              "memberCouponId":1001,"storeId":10,"storeName":"런치식당",
                              "storeImageUrl":"https://images.example.org/menu-v1.jpg?size=640&variant=2",
                              "discountTargetType":"MENU","discountType":"AMOUNT","discountValue":3000,
                              "targetMenu":{"menuId":701,"name":"숯불갈비 정식","price":12000,"discountedPrice":9000},
                              "state":"ISSUED","usable":true,"issueTime":"2026-10-10T10:30:00+09:00",
                              "usableStartTime":"2026-10-10T11:00:00+09:00",
                              "usableEndTime":"2026-10-10T14:00:00+09:00","useTime":null,"discountAmount":null
                            }}
                            """))
                    .andExpect(jsonPath("$.data.eventId").doesNotExist())
                    .andExpect(jsonPath("$.data.qrToken").doesNotExist());

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 이미지_URL이_255자를_넘어도_쿼리_문자열까지_그대로_저장하고_반환한다() throws Exception {
            String imageUrl = "https://images.example.org/" + "a".repeat(350) + "/menu.jpg?quality=90";
            jdbcTemplate.update("UPDATE coupon_event SET store_image_url = ? WHERE coupon_event_id = 81", imageUrl);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeImageUrl").value(imageUrl));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT store_image_url FROM coupon_event WHERE coupon_event_id = 81", String.class)).isEqualTo(imageUrl);
        }

        @Test
        void 만료를_저장해도_QR과_발급_횟수와_재고와_사용_이력은_유지한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                    .andExpect(jsonPath("$.data.usable").value(false));

            assertThat(findCoupon().get("status")).isEqualTo("EXPIRED");
            assertThat(findCoupon()).usingRecursiveComparison().ignoringFields("status", "updated_at").isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usagesBefore);
        }

        @ParameterizedTest
        @CsvSource({
                "2026-10-10T01:59:59.999999Z, ISSUED, false",
                "2026-10-10T02:00:00Z, ISSUED, true",
                "2026-10-10T04:59:59.999999Z, ISSUED, true",
                "2026-10-10T05:00:00Z, EXPIRED, false"
        })
        void 사용_시작은_포함하고_종료부터_만료를_저장한다(Instant now, String state, boolean usable) throws Exception {
            setTime(now);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value(state))
                    .andExpect(jsonPath("$.data.usable").value(usable));

            assertThat(findCoupon().get("status")).isEqualTo(state);
        }

        @Test
        void 만료_쿠폰을_다시_조회하면_추가로_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'EXPIRED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                    .andExpect(jsonPath("$.data.usable").value(false));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 3000})
        void 사용_완료_쿠폰은_기간이_지나도_사용_상태와_확정_결과를_반환한다(int discountAmount) throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1001, 10, ?, ?, ?)
                    """, discountAmount, NOW, NOW);
            Map<String, Object> before = findCoupon();
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("USED"))
                    .andExpect(jsonPath("$.data.usable").value(false))
                    .andExpect(jsonPath("$.data.useTime").value("2026-10-10T12:00:00+09:00"))
                    .andExpect(jsonPath("$.data.discountAmount").value(discountAmount));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usagesBefore);
        }

        @Test
        void 삭제된_본인_쿠폰은_404로_거절하고_만료로_변경하지_않는다() throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            jdbcTemplate.update("UPDATE member_coupon SET status = 'REMOVED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COUPON-001"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 사용_완료_이력이_누락되면_정합성_오류_코드로_500을_반환한다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1001", "9999"})
        void 남의_쿠폰과_없는_ID는_동일한_403으로_거절한다(String id) throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get("/v1/coupons/" + id).with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_ID는_400으로_거절한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "9223372036854775808"})
        void 정수로_해석할_수_없는_ID는_400으로_거절한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 인증되지_않은_요청은_401로_거절한다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
        void MEMBER_외의_역할은_403으로_거절한다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 만료_상태_저장이_실패하면_변경을_롤백하고_성공_응답을_반환하지_않는다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            Map<String, Object> before = findCoupon();
            jdbcTemplate.execute("""
                    ALTER TABLE member_coupon ADD CONSTRAINT chk_test_expiry_failure CHECK (status <> 'EXPIRED')
                    """);
            try {
                mockMvc.perform(get(PATH).with(user(MEMBER)))
                        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            } finally {
                jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_expiry_failure");
            }
        }

        @Test
        void 만료_조회가_먼저_잠그면_삭제는_만료_저장_뒤에_진행한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> deletion = new TransactionTemplate(transactionManager).execute(transaction -> {
                    assertThat(couponLookUpService.getDetail(17L, 1001L).state()).isEqualTo(MemberCouponStatus.EXPIRED);
                    Future<?> result = executor.submit(() -> {
                        started.countDown();
                        mockMvc.perform(delete(PATH).with(user(MEMBER))).andExpect(status().isNoContent());
                        return null;
                    });
                    assertBlocked(result, started);
                    return result;
                });

                deletion.get(5, TimeUnit.SECONDS);
            }

            assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit", Integer.class)).isZero();
        }

        @Test
        void 삭제가_먼저_잠그면_조회는_삭제_저장_뒤에_404로_거절한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                    couponCommandService.deleteCoupon(17L, 1001L);
                    Future<?> result = executor.submit(() -> {
                        started.countDown();
                        mockMvc.perform(get(PATH).with(user(MEMBER)))
                                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COUPON-001"));
                        return null;
                    });
                    assertBlocked(result, started);
                    return result;
                });

                query.get(5, TimeUnit.SECONDS);
            }

            assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            assertThat(findCoupon().get("qr_token")).isNull();
        }

        @Nested
        @DisplayName("목록 조회의 만료 저장과 동시 요청")
        class ListMutationQuery {

            @Test
            void 만료_저장은_QR과_발급_횟수와_재고와_사용_이력을_보존한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                Map<String, Object> before = findCoupon();
                var limit = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
                var inventory = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");
                var usages = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

                mockMvc.perform(get("/v1/coupons").with(user(MEMBER)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].state").value("EXPIRED"))
                        .andExpect(jsonPath("$.data.items[0].usable").value(false))
                        .andExpect(jsonPath("$.data.items[0].storeImageUrl")
                                .value("https://images.example.org/menu-v1.jpg?size=640&variant=2"));

                assertThat(findCoupon().get("status")).isEqualTo("EXPIRED");
                assertThat(findCoupon()).usingRecursiveComparison().ignoringFields("status", "updated_at").isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limit);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                        .usingRecursiveComparison().isEqualTo(inventory);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usages);
            }

            @Test
            void 만료_저장이_실패하면_변경을_롤백한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                Map<String, Object> before = findCoupon();
                jdbcTemplate.execute("ALTER TABLE member_coupon ADD CONSTRAINT chk_test_list_expiry CHECK (status <> 'EXPIRED')");
                try {
                    mockMvc.perform(get("/v1/coupons").with(user(MEMBER)))
                            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
                    assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                } finally {
                    jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_list_expiry");
                }
            }

            @Test
            void 목록이_먼저_잠그면_삭제는_만료_저장_뒤에_진행한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> deletion = new TransactionTemplate(transactionManager).execute(transaction -> {
                        assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                                .isEqualTo(MemberCouponStatus.EXPIRED);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            couponCommandService.deleteCoupon(17L, 1001L);
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    deletion.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
                assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit", Integer.class)).isZero();
            }

            @Test
            void 삭제가_먼저_잠그면_목록은_삭제된_쿠폰을_제외한다() throws Exception {
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                        couponCommandService.deleteCoupon(17L, 1001L);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items()).isEmpty();
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    query.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            }

            @Test
            void 사용_저장이_먼저_잠그면_목록은_확정_사용_결과를_반환한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                        saveUsage();
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            var item = couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst();
                            assertThat(item.state()).isEqualTo(MemberCouponStatus.USED);
                            assertThat(item.discountAmount()).isEqualTo(3000);
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    query.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("USED");
            }

            @Test
            void 목록이_먼저_잠그면_사용_저장은_목록_트랜잭션_뒤에_진행한다() throws Exception {
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> usage = new TransactionTemplate(transactionManager).execute(transaction -> {
                        assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                                .isEqualTo(MemberCouponStatus.ISSUED);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> saveUsage());
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    usage.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("USED");
                assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().discountAmount()).isEqualTo(3000);
            }

            // 사용 API 구현 전이므로 같은 쿠폰 행을 잠그는 실제 DB 트랜잭션으로 상태와 사용 이력을 함께 저장한다.
            private void saveUsage() {
                jdbcTemplate.queryForList("SELECT member_coupon_id FROM member_coupon WHERE member_coupon_id = 1001 FOR UPDATE");
                jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
                jdbcTemplate.update("""
                        INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                        VALUES (1001, 10, 3000, ?, ?)
                        """, NOW, NOW);
            }
        }

        private void assertBlocked(Future<?> result, CountDownLatch started) {
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            assertThatThrownBy(() -> result.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }

        private void setTime(Instant instant) {
            when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(instant, ClockConfig.ZONE));
            when(clock.getZone()).thenReturn(ClockConfig.ZONE);
            when(clock.instant()).thenReturn(instant);
        }

        private Map<String, Object> findCoupon() {
            return jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id = 1001");
        }
    }

    @Nested
    @DisplayName("쿠폰 삭제")
    class DeleteCoupon {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            when(clock.getZone()).thenReturn(ClockConfig.ZONE);
            when(clock.instant()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (81, 1, UNHEX('00112233445566778899AABBCCDDEEFF'), 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at,
                            created_at, updated_at)
                    VALUES (1001, 81, 17, UNHEX('00112233445566778899AABBCCDDEEFF'), 3,
                            UNHEX('FFEEDDCCBBAA99887766554433221100'), ?, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.plusMinutes(1), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, NOW.toLocalDate(), NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @ParameterizedTest
        @EnumSource(value = MemberCouponStatus.class, names = {"ISSUED", "EXPIRED"})
        void 삭제는_204를_반환하고_발급_횟수를_차감하며_QR을_무효화한다(MemberCouponStatus state)
                throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = ? WHERE member_coupon_id = 1001", state.name());
            Map<String, Object> before = findCoupon();
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));

            Map<String, Object> coupon = findCoupon();
            assertThat(coupon.get("status")).isEqualTo("REMOVED");
            assertThat(coupon.get("qr_version")).isEqualTo(4);
            assertThat(coupon.get("qr_token")).isNull();
            assertThat(coupon.get("qr_expires_at")).isNull();
            assertThat(coupon).usingRecursiveComparison()
                    .ignoringFields("status", "qr_version", "qr_token", "qr_expires_at", "updated_at")
                    .isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.issuedCount").value(0));
            mockMvc.perform(get("/v1/coupons/summary").with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedCouponCount").value(1));
        }

        @Test
        void 삭제를_재요청해도_204를_반환하고_추가로_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER))).andExpect(status().isNoContent());
            Map<String, Object> removed = findCoupon();

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(removed);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 사용_완료_쿠폰은_409를_반환하고_사용_이력과_QR을_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1001, 10, 3000, ?, ?)
                    """, NOW, NOW);
            Map<String, Object> before = findCoupon();

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-012"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject("SELECT discount_amount FROM coupon_usage_history", Integer.class))
                    .isEqualTo(3000);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1001", "9999"})
        void 다른_회원_쿠폰과_없는_ID는_동일한_403으로_거절한다(String id) throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(delete("/v1/coupons/" + id).with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 다른_회원이_이미_삭제된_쿠폰을_요청해도_소유권_검사를_생략하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'REMOVED' WHERE member_coupon_id = 1001");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));
        }

        @Test
        void 인증_없이는_삭제할_수_없다() throws Exception {
            mockMvc.perform(delete("/v1/coupons/1001"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
            assertThat(findCoupon().get("status")).isEqualTo("ISSUED");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
        void 회원_외_역할은_삭제할_수_없다(Role role) throws Exception {
            mockMvc.perform(delete("/v1/coupons/1001").with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
            assertThat(findCoupon().get("status")).isEqualTo("ISSUED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_쿠폰_ID는_공통_입력_오류로_반환한다(String id) throws Exception {
            mockMvc.perform(delete("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 정수로_해석할_수_없는_쿠폰_ID는_공통_해석_오류로_반환한다() throws Exception {
            mockMvc.perform(delete("/v1/coupons/abc").with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 같은_쿠폰을_동시에_삭제해도_발급_횟수는_한_번만_차감한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            deleteConcurrently(1001L,
                    "SELECT member_coupon_id FROM member_coupon WHERE member_coupon_id = 1001 FOR UPDATE");

            Map<String, Object> coupon = findCoupon();
            assertThat(coupon.get("status")).isEqualTo("REMOVED");
            assertThat(coupon.get("qr_version")).isEqualTo(4);
            assertThat(coupon.get("qr_token")).isNull();
            assertThat(coupon.get("qr_expires_at")).isNull();
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 같은_날_발급한_서로_다른_쿠폰을_동시에_삭제하면_각각_차감한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (82, 82, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (1002, 82, 17, UNHEX('11112233445566778899AABBCCDDEEFF'), 0, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);

            deleteConcurrently(1002L,
                    "SELECT coupon_daily_limit_id FROM coupon_daily_limit WHERE member_id = 17 FOR UPDATE");

            assertThat(jdbcTemplate.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("REMOVED", "REMOVED");
            assertThat(findIssuedCount()).isZero();
        }

        @Test
        void 과거에_발급한_쿠폰을_오늘_삭제해도_발급받은_날짜의_횟수만_줄인다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET created_at = ? WHERE member_coupon_id = 1001", NOW.minusDays(1));
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 3 WHERE member_id = 17");
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 2, ?, ?)
                    """, NOW.toLocalDate().minusDays(1), NOW.minusDays(1), NOW.minusDays(1));

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT issued_count FROM coupon_daily_limit WHERE member_id = 17 AND business_date = ?",
                    Integer.class, NOW.toLocalDate().minusDays(1))).isEqualTo(1);
            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issuedCount").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(true));
        }

        @Test
        void 삭제로_횟수가_줄면_일일_발급_한도에_다시_여유가_생긴다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 3 WHERE member_id = 17");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issuedCount").value(2))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));
        }

        private void deleteConcurrently(long secondCouponId, String lockQuery) throws Exception {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> deleteTogether(1001L, ready, start));
                var second = executor.submit(() -> deleteTogether(secondCouponId, ready, start));
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                        jdbcTemplate.queryForObject(lockQuery, Long.class);
                        try {
                            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        start.countDown();
                        assertThatThrownBy(() -> first.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                    });
                } finally {
                    start.countDown();
                }

                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            }

        }

        @Test
        void 쿠폰_저장이_실패하면_횟수_차감과_상태와_QR_변경을_함께_롤백한다() throws Exception {
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            jdbcTemplate.execute("""
                    ALTER TABLE member_coupon ADD CONSTRAINT chk_test_delete_failure CHECK (status <> 'REMOVED')
                    """);
            try {
                mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            } finally {
                jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_delete_failure");
            }
        }

        private Void deleteTogether(long couponId, CountDownLatch ready, CountDownLatch start) throws Exception {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(delete("/v1/coupons/" + couponId).with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
            return null;
        }

        @Test
        void 횟수_저장이_실패해도_쿠폰_상태와_QR과_횟수_차감을_함께_롤백한다() throws Exception {
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            jdbcTemplate.execute("""
                    ALTER TABLE coupon_daily_limit ADD CONSTRAINT chk_test_count_failure CHECK (issued_count > 0)
                    """);
            try {
                mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            } finally {
                jdbcTemplate.execute("ALTER TABLE coupon_daily_limit DROP CHECK chk_test_count_failure");
            }
        }

        // 삭제 전후의 상태와 QR 정보를 비교할 회원 쿠폰을 DB에서 조회한다.
        private Map<String, Object> findCoupon() {
            return jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id = 1001");
        }

        private int findIssuedCount() {
            return jdbcTemplate.queryForObject(
                    "SELECT issued_count FROM coupon_daily_limit WHERE member_id = 17 AND business_date = ?",
                    Integer.class, NOW.toLocalDate());
        }
    }
}
