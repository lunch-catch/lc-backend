package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * GET /v1/feed 를 HTTP 입구부터 DB 와 Valkey 까지 한 번에 확인한다.
 * 회원과 정산은 다른 도메인이라 내부 테이블을 채우지 않고 contract 빈을 바꿔 끼운다.
 * 예산 조회는 develop 에 구현이 없어(#193) 이 테스트가 빈을 넣어 예산 갈래까지 돈다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("스와이프 피드 조회")
class FeedIntegrationTest {

    private static final Long MEMBER_ID = 3001L;
    private static final CustomUserDetails MEMBER = new CustomUserDetails(MEMBER_ID, Role.MEMBER);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");
    private static final double LATITUDE = 37.4979;
    private static final double LONGITUDE = 127.0276;

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
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(NOW, ClockConfig.ZONE));
        when(clock.instant()).thenReturn(NOW);
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(
                MEMBER_ID, "닉네임", MemberStatus.ACTIVE, true, false, true,
                BigDecimal.valueOf(LATITUDE), BigDecimal.valueOf(LONGITUDE), Gender.FEMALE, AgeGroup.AGE_30S)));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM impression_log");
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void MEMBER_요청에_예산과_노출_상한에_걸리지_않은_캠페인만_카드로_내려주고_serve_기록을_남긴다() throws Exception {
        insertCandidate(101, 420);
        insertCandidate(102, 430);
        insertCandidate(103, 440);
        // 102 는 예약액 1000 원, 단가 10 원이라 상한이 105 회이고 이미 105 회 유효 노출이 있다
        insertValidImpressions(102, 105);
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(LocalDateTime.of(2026, 10, 1, 10, 15, 30))))
                .thenReturn(List.of(progress(101, 0.5), progress(102, 0.5), progress(103, 1.3)));

        mockMvc.perform(get("/v1/feed").with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards.length()").value(1))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.cards[0].servedAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards[0].distanceM").value(420))
                .andExpect(jsonPath("$.data.cards[0].discount.target").value("ALL"))
                .andExpect(jsonPath("$.data.cards[0].usableStartTime").value("12:00"))
                .andExpect(jsonPath("$.data.cards[0].posterUrl").value("/v1/campaigns/101/poster"));

        assertThat(redisTemplate.keys("adserving:serve:*")).hasSize(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForList(
                    "SELECT campaign_id FROM serve_log WHERE member_id = ? AND business_date = ?",
                    Long.class, MEMBER_ID, TODAY)).containsExactly(101L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT lat_rounded FROM feed_visit_daily WHERE member_id = ? AND business_date = ?",
                    BigDecimal.class, MEMBER_ID, TODAY)).isEqualByComparingTo("37.498");
        });
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이다(Role role) throws Exception {
        mockMvc.perform(get("/v1/feed").with(user(new CustomUserDetails(MEMBER_ID, role))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 최근_60초에_6회를_넘으면_429_와_Retry_After_를_주고_거부한_요청은_세지_않는다() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/v1/feed").with(user(MEMBER))).andExpect(status().isOk());
        }

        mockMvc.perform(get("/v1/feed").with(user(MEMBER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("ADSERVING-003"));
        assertThat(redisTemplate.opsForZSet().size("adserving:feed:requests:" + MEMBER_ID)).isEqualTo(6L);
    }

    @Test
    void 예산_조회가_실패해_500_으로_끝난_요청은_횟수에_넣지_않는다() throws Exception {
        insertCandidate(101, 420);
        when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(LocalDateTime.of(2026, 10, 1, 10, 15, 30))))
                .thenThrow(new IllegalStateException("counter down"));

        mockMvc.perform(get("/v1/feed").with(user(MEMBER)))
                .andExpect(status().isInternalServerError());

        assertThat(redisTemplate.opsForZSet().size("adserving:feed:requests:" + MEMBER_ID)).isZero();
    }

    @Test
    void 위도와_경도_중_하나만_보내면_400_COMMON_002_다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", "37.4979").with(user(MEMBER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    private void insertCandidate(long campaignId, int metersNorth) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate
                    (business_date, campaign_id, store_id, store_name, category_code, latitude, longitude,
                     target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                     discount_value, usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                     campaign_state, created_at, updated_at)
                VALUES (?, ?, 55, '점심식당', 'KOREAN', ?, ?, 1000, 'ALL', 'AGE_30S', 'ALL', 'PERCENT',
                        20, '12:00', '14:00', 30, ?, 'SERVABLE', NOW(6), NOW(6))
                """, TODAY, campaignId, LATITUDE + metersNorth / 111_195.0, LONGITUDE,
                LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(campaignId));
    }

    private void insertValidImpressions(long campaignId, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO impression_log
                        (serve_id, member_id, business_date, campaign_id, displayed_at, lat_rounded, lng_rounded,
                         validity, created_at, updated_at)
                    VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, NOW(6), 37.498, 127.028, 'VALID', NOW(6), NOW(6))
                    """, MEMBER_ID + i, TODAY, campaignId);
        }
    }

    private static BudgetProgress progress(long campaignId, double rate) {
        return new BudgetProgress(campaignId, 1000, 10, 0, 0, rate);
    }
}
