package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * GET /v1/feed 를 HTTP 입구부터 MySQL 과 Valkey 까지 한 번에 확인한다.
 * 다른 도메인은 contract 만 목으로 바꾼다. 회원 정보와 예산 진행률은 그 도메인이 소유한 값이다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("스와이프 피드 조회 통합")
class FeedIntegrationTest {

    private static final Long MEMBER_ID = 17L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");
    private static final String LATITUDE = "37.4979";
    private static final String LONGITUDE = "127.0276";
    private static final CustomUserDetails MEMBER = new CustomUserDetails(MEMBER_ID, Role.MEMBER);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoBean Clock clock;
    @MockitoBean MemberQueryService memberQueryService;
    @MockitoBean BudgetQueryService budgetQueryService;

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
    void setUp() {
        when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(NOW, ClockConfig.ZONE));
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(NOW);
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true)));
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(LocalDateTime.ofInstant(NOW, ClockConfig.ZONE))))
                .thenAnswer(invocation -> progressOf(invocation.getArgument(0)));
        List.of("ad_candidate", "serve_log", "feed_visit_daily", "swipe_log", "impression_log",
                "feed_filter_category", "feed_filter").forEach(table -> jdbcTemplate.update("DELETE FROM " + table));
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void 카드를_명세의_필드로_내려주고_서빙_로그와_접속_위치와_serve_기록을_남긴다() throws Exception {
        insertCandidate(101L, 420);

        feed().andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.feedId").isString())
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards.length()").value(1))
                .andExpect(jsonPath("$.data.cards[0].serveId").isString())
                .andExpect(jsonPath("$.data.cards[0].servedAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.cards[0].storeId").value(1101))
                .andExpect(jsonPath("$.data.cards[0].storeName").value("가게101"))
                .andExpect(jsonPath("$.data.cards[0].categoryCode").value("KOREAN"))
                .andExpect(jsonPath("$.data.cards[0].distanceM").value(420))
                .andExpect(jsonPath("$.data.cards[0].discount.target").value("ALL"))
                .andExpect(jsonPath("$.data.cards[0].discount.type").value("PERCENT"))
                .andExpect(jsonPath("$.data.cards[0].discount.value").value(20))
                .andExpect(jsonPath("$.data.cards[0].issueOpenTime").value("11:00"))
                .andExpect(jsonPath("$.data.cards[0].usableStartTime").value("12:00"))
                .andExpect(jsonPath("$.data.cards[0].usableEndTime").value("14:00"))
                .andExpect(jsonPath("$.data.cards[0].issueQuantity").value(30))
                .andExpect(jsonPath("$.data.cards[0].posterUrl").value("/v1/campaigns/101/poster"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForList(
                    "SELECT slot_type FROM serve_log WHERE member_id = ? AND campaign_id = 101", String.class, MEMBER_ID))
                    .containsExactly("ALLOCATION");
            assertThat(jdbcTemplate.queryForList(
                    "SELECT CONCAT(lat_rounded, ',', lng_rounded) FROM feed_visit_daily WHERE member_id = ?",
                    String.class, MEMBER_ID))
                    .containsExactly("37.498,127.028");
        });
        assertThat(redisTemplate.keys("adserving:serve:*")).hasSize(1);
    }

    @Test
    void 같은_세션에서는_이미_받은_캠페인을_다시_내려주지_않는다() throws Exception {
        insertCandidate(101L, 420);

        feed().andExpect(jsonPath("$.data.status").value("SERVING"));
        feed().andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"))
                .andExpect(jsonPath("$.data.feedId").value(nullValue()))
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.cards.length()").value(0));
    }

    @Test
    void 서빙할_수_없거나_소진됐거나_오늘_넘겼거나_노출_상한에_닿은_캠페인은_내려주지_않는다() throws Exception {
        insertCandidate(101L, 100);
        insertCandidate(102L, 200);
        insertCandidate(103L, 300);
        insertCandidate(104L, 400);
        insertCandidate(105L, 500);
        jdbcTemplate.update("UPDATE ad_candidate SET campaign_state = 'PAUSED' WHERE campaign_id = 102");
        jdbcTemplate.update("UPDATE ad_candidate SET sold_out_at = NOW(6) WHERE campaign_id = 103");
        jdbcTemplate.update("""
                INSERT INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action,
                                       occurred_at, created_at, updated_at)
                VALUES (UUID_TO_BIN(UUID()), ?, ?, 104, 'KOREAN', 'PASS', NOW(6), NOW(6), NOW(6))
                """, MEMBER_ID, TODAY);
        // 105 는 예약액 100 원, 단가 10 원이라 노출 상한이 10 회다
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(LocalDateTime.ofInstant(NOW, ClockConfig.ZONE))))
                .thenReturn(List.of(new BudgetProgress(101L, 10_000, 10, 0, 0, 0.5),
                        new BudgetProgress(105L, 100, 10, 0, 0, 0.5)));
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update("""
                    INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, displayed_at,
                                                lat_rounded, lng_rounded, validity, created_at, updated_at)
                    VALUES (UUID_TO_BIN(UUID()), ?, ?, 105, NOW(6), 37.498, 127.028, 'VALID', NOW(6), NOW(6))
                    """, 900L + i, TODAY);
        }

        feed().andExpect(jsonPath("$.data.cards[*].campaignId").value(contains(101)));
    }

    @Test
    void 피드_필터의_업종에_없는_캠페인은_내려주지_않는다() throws Exception {
        insertCandidate(101L, 100);
        insertCandidate(102L, 200);
        jdbcTemplate.update("UPDATE ad_candidate SET category_code = 'JAPANESE' WHERE campaign_id = 102");
        jdbcTemplate.update("INSERT INTO feed_filter VALUES (?, 'DISTANCE', NOW(6), NOW(6))", MEMBER_ID);
        jdbcTemplate.update("INSERT INTO feed_filter_category VALUES (?, 'JAPANESE', NOW(6), NOW(6))", MEMBER_ID);

        feed().andExpect(jsonPath("$.data.cards[*].campaignId").value(contains(102)));
    }

    @Test
    void 최근_60초_6회를_넘으면_429_와_Retry_After_를_주고_200_이_아닌_요청은_세지_않는다() throws Exception {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false)));
        feed().andExpect(status().isForbidden());
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true)));

        for (int i = 0; i < 5; i++) {
            feed().andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:15:30+09:00"));
        }
        feed().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:16:30+09:00"));
        feed().andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("ADSERVING-003"))
                .andExpect(jsonPath("$.message").value("잠시 후 다시 시도해 주세요."));

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        feed().andExpect(status().isOk());
    }

    @Test
    void 서빙_시간대_밖이면_OUT_OF_SERVING_HOURS_다() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-01T04:00:00Z"));

        feed().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OUT_OF_SERVING_HOURS"))
                .andExpect(jsonPath("$.data.feedId").value(nullValue()))
                .andExpect(jsonPath("$.data.radiusM").value(nullValue()))
                .andExpect(jsonPath("$.data.cards.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "latitude=37.4979",
            "longitude=127.0276",
            "latitude=91&longitude=127.0276",
            "latitude=37.4979&longitude=-180.1",
            "latitude=abc&longitude=127.0276"
    })
    void 위도와_경도_중_하나만_보내거나_범위_밖이면_400_COMMON_002_다(String query) throws Exception {
        mockMvc.perform(get("/v1/feed?" + query).with(user(MEMBER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 온보딩을_마치지_않았으면_403_ADSERVING_001_이다() throws Exception {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false)));

        feed().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADSERVING-001"))
                .andExpect(jsonPath("$.message").value("온보딩을 완료한 뒤 피드를 이용할 수 있습니다."));
    }

    @Test
    void 위치를_보내지_않았고_저장_위치도_없으면_422_ADSERVING_002_다() throws Exception {
        mockMvc.perform(get("/v1/feed").with(user(MEMBER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ADSERVING-002"))
                .andExpect(jsonPath("$.message").value("위치를 설정한 뒤 피드를 이용할 수 있습니다."));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
    void MEMBER_가_아니면_403_이다(Role role) throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                        .with(user(new CustomUserDetails(MEMBER_ID, role))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 인증하지_않으면_401_이다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    private ResultActions feed() throws Exception {
        return mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                .with(user(MEMBER)));
    }

    // 요청 위치에서 북쪽으로 metersNorth 만큼 떨어진 가게의 후보를 넣는다
    private void insertCandidate(Long campaignId, int metersNorth) {
        double latitude = Double.parseDouble(LATITUDE) + metersNorth / 111_195.08;
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code, latitude,
                    longitude, target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                    discount_value, usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                    campaign_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'KOREAN', ?, ?, 3000, 'ALL', 'AGE_20S,AGE_30S', 'ALL', 'PERCENT', 20,
                    '12:00:00', '14:00:00', 30, '2026-09-01 09:00:00', 'SERVABLE', NOW(6), NOW(6))
                """, TODAY, campaignId, campaignId + 1000, "가게" + campaignId, latitude, Double.parseDouble(LONGITUDE));
    }

    private static MemberInfo member(boolean onboarded) {
        return new MemberInfo(MEMBER_ID, "점심", MemberStatus.ACTIVE, onboarded, true, true,
                (BigDecimal) null, null, onboarded ? Gender.FEMALE : null, onboarded ? AgeGroup.AGE_20S : null);
    }

    // 예약액 10000 원, 단가 10 원이라 노출 상한은 1050 회다
    private static List<BudgetProgress> progressOf(Collection<Long> campaignIds) {
        return campaignIds.stream().map(id -> new BudgetProgress(id, 10_000, 10, 0, 0, 0.5)).toList();
    }
}
