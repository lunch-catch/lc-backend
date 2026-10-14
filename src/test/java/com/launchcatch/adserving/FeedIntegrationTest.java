package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.IntegrationTestSupport;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/*
 * GET /v1/feed 를 HTTP 입구부터 DB 와 Valkey 저장까지 확인한다.
 * 회원과 정산은 다른 도메인이라 contract 만 가짜로 바꾼다. 후보, 노출 로그, serve 기록, 요청 제한은 실제 저장소를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedIntegrationTest extends IntegrationTestSupport {

    private static final long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30Z");
    private static final CustomUserDetails MEMBER = new CustomUserDetails(MEMBER_ID, Role.MEMBER);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoBean Clock clock;
    @MockitoBean MemberQueryService memberQueryService;
    @MockitoBean BudgetQueryService budgetQueryService;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(NOW, ClockConfig.ZONE));
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(MEMBER_ID, "점심러",
                MemberStatus.ACTIVE, true, true, true, null, null, Gender.FEMALE, AgeGroup.AGE_30S)));
        // 예약액 10,000원, 단가 10원이라 노출 상한은 1,050회다
        when(budgetQueryService.getProgress(any(), eq(TODAY), any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new BudgetProgress(id, 10_000, 10, 1_000, 2_000, 0.5)).toList();
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM impression_log");
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        redisTemplate.delete(redisTemplate.keys("adserving:*"));
    }

    private static MockHttpServletRequestBuilder feed() {
        return get("/v1/feed").param("latitude", "37.4979").param("longitude", "127.0276");
    }

    private void insertCandidate(long campaignId) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 0, 0);
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code, latitude, longitude,
                    target_radius, target_gender, target_age_groups, discount_target_type, discount_type, discount_value,
                    usable_start_time, usable_end_time, issue_quantity, campaign_created_at, campaign_state, created_at, updated_at)
                VALUES (?, ?, 55, '점심식당', 'KOREAN', 37.5016000, 127.0276000, 1000, 'ALL', 'AGE_30S', 'ALL', 'PERCENT', 20,
                    '12:00:00', '14:00:00', 30, ?, 'SERVABLE', ?, ?)
                """, TODAY, campaignId, now, now, now);
    }

    private void insertValidImpressions(long campaignId, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update("""
                    INSERT INTO impression_log (serve_id, member_id, business_date, campaign_id, displayed_at, lat_rounded,
                        lng_rounded, validity, created_at, updated_at)
                    VALUES (UUID_TO_BIN(UUID()), ?, ?, ?, ?, 37.498, 127.028, 'VALID', ?, ?)
                    """, i + 1, TODAY, campaignId, LocalDateTime.of(2026, 10, 1, 10, 0), LocalDateTime.now(),
                    LocalDateTime.now());
        }
    }

    // 서빙 로그는 응답 뒤에 대기열에서 쓰므로 행이 생길 때까지 기다린다
    private Integer awaitServeLogCount(int expected) throws InterruptedException {
        Integer count = 0;
        for (int i = 0; i < 50; i++) {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM serve_log", Integer.class);
            if (count >= expected) {
                return count;
            }
            Thread.sleep(100);
        }
        return count;
    }

    @Test
    void 사용자에게_카드를_내려주고_serve_기록과_서빙_로그와_접속_위치를_남긴다() throws Exception {
        insertCandidate(101);

        String serveId = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(feed().with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.cards[0].storeName").value("점심식당"))
                .andExpect(jsonPath("$.data.cards[0].distanceM").value(411))
                .andExpect(jsonPath("$.data.cards[0].discount.type").value("PERCENT"))
                .andExpect(jsonPath("$.data.cards[0].usableEndTime").value("14:00"))
                .andExpect(jsonPath("$.data.cards[0].posterUrl").value("/v1/campaigns/101/poster"))
                .andReturn().getResponse().getContentAsString(), "$.data.cards[0].serveId");

        assertThat(redisTemplate.<String, String>opsForHash().entries("adserving:serve:" + serveId))
                .containsEntry("memberId", "3001").containsEntry("campaignId", "101")
                .containsEntry("slotType", "ALLOCATION");
        assertThat(awaitServeLogCount(1)).isEqualTo(1);
        Map<String, Object> visit = jdbcTemplate.queryForMap("SELECT * FROM feed_visit_daily WHERE member_id = ?", MEMBER_ID);
        assertThat(visit.get("lat_rounded").toString()).isEqualTo("37.498");
    }

    @Test
    void 그날_유효_노출이_노출_상한에_닿은_캠페인은_내려주지_않는다() throws Exception {
        insertCandidate(101);
        insertCandidate(102);
        jdbcTemplate.update("UPDATE ad_candidate SET store_id = 56 WHERE campaign_id = 102");
        insertValidImpressions(102, 2);
        when(budgetQueryService.getProgress(any(), eq(TODAY), any())).thenReturn(List.of(
                new BudgetProgress(101L, 10_000, 10, 1_000, 2_000, 0.5),
                new BudgetProgress(102L, 10, 10, 10, 10, 1.0)));

        mockMvc.perform(feed().with(user(MEMBER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards.length()").value(1))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));
    }

    @Test
    void MEMBER가_아니면_403이고_로그인하지_않았으면_401이다() throws Exception {
        mockMvc.perform(feed().with(user(new CustomUserDetails(MEMBER_ID, Role.OWNER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(feed()).andExpect(status().isUnauthorized());
    }

    @Test
    void 최근_60초_일곱째_요청은_Retry_After와_함께_429다() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(feed().with(user(MEMBER))).andExpect(status().isOk());
        }

        mockMvc.perform(feed().with(user(MEMBER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("ADSERVING-003"));
    }

    @Test
    void 실패한_요청은_요청_횟수에_들어가지_않는다() throws Exception {
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/v1/feed").with(user(MEMBER))).andExpect(status().isUnprocessableEntity());
        }

        mockMvc.perform(feed().with(user(MEMBER))).andExpect(status().isOk());
    }

    @Test
    void 위도와_경도_중_하나만_보내면_400_COMMON_002다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", "37.4979").with(user(MEMBER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }
}
