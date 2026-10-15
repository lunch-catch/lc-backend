package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/*
 * GET /v1/feed 를 HTTP 입구부터 MySQL 의 ad_candidate 와 Valkey 의 요청 제한까지 한 번에 본다.
 * 권한이 이 테스트의 축이다. 요청 제한은 권한 축이 아니지만 Lua 스크립트가 단위 테스트에서 한 번도 돌지 않아 여기서 함께 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("피드 조회 통합")
class FeedIntegrationTest extends IntegrationTestSupport {

    private static final long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant SERVING_NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 30)
            .atZone(ClockConfig.ZONE).toInstant();
    private static final double LAT = 37.4979;
    private static final double LNG = 127.0276;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @MockitoSpyBean Clock clock;
    @MockitoBean MemberQueryService memberQueryService;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(SERVING_NOW);
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(
                MEMBER_ID, "점심러", MemberStatus.ACTIVE, true, true, true,
                BigDecimal.valueOf(LAT), BigDecimal.valueOf(LNG), Gender.FEMALE, AgeGroup.AGE_30S)));
        insertCandidate(101L, "SERVABLE", null);
        insertCandidate(102L, "PAUSED", null);
        insertCandidate(103L, "SERVABLE", LocalDateTime.of(2026, 10, 1, 9, 0));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
        redisTemplate.delete("adserving:feedRequests:" + MEMBER_ID);
    }

    private void insertCandidate(long campaignId, String state, LocalDateTime soldOutAt) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, sold_out_at, created_at, updated_at)
                VALUES (?, ?, 55, '점심식당', 'KOREAN', ?, ?, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), ?, ?, NOW(6), NOW(6))
                """, TODAY, campaignId, LAT, LNG, state, soldOutAt);
    }

    private static CustomUserDetails as(Role role) {
        return new CustomUserDetails(MEMBER_ID, role);
    }

    @Test
    void MEMBER_는_그날_서빙할_수_있는_후보만_명세의_모양으로_받는다() throws Exception {
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.feedId").isString())
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards.length()").value(1))
                .andExpect(jsonPath("$.data.cards[0].serveId").isString())
                .andExpect(jsonPath("$.data.cards[0].servedAt").value("2026-10-01T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.cards[0].storeId").value(55))
                .andExpect(jsonPath("$.data.cards[0].storeName").value("점심식당"))
                .andExpect(jsonPath("$.data.cards[0].categoryCode").value("KOREAN"))
                .andExpect(jsonPath("$.data.cards[0].distanceM").value(0))
                .andExpect(jsonPath("$.data.cards[0].discount.target").value("ALL"))
                .andExpect(jsonPath("$.data.cards[0].discount.type").value("PERCENT"))
                .andExpect(jsonPath("$.data.cards[0].discount.value").value(20))
                .andExpect(jsonPath("$.data.cards[0].issueOpenTime").value("11:00"))
                .andExpect(jsonPath("$.data.cards[0].usableStartTime").value("12:00"))
                .andExpect(jsonPath("$.data.cards[0].usableEndTime").value("14:00"))
                .andExpect(jsonPath("$.data.cards[0].issueQuantity").value(30))
                .andExpect(jsonPath("$.data.cards[0].posterUrl").value("/v1/campaigns/101/poster"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이고_요청_횟수에_넣지_않는다(Role role) throws Exception {
        mockMvc.perform(get("/v1/feed").with(user(as(role))))
                .andExpect(status().isForbidden());

        assertThat(redisTemplate.hasKey("adserving:feedRequests:" + MEMBER_ID)).isFalse();
    }

    @Test
    void 로그인하지_않으면_401_이다() throws Exception {
        mockMvc.perform(get("/v1/feed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 위도와_경도_중_하나만_보내면_COMMON_002_다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", "37.5").with(user(as(Role.MEMBER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 범위_밖의_좌표는_COMMON_002_다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", "90.1").param("longitude", "127")
                        .with(user(as(Role.MEMBER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 최근_60초_안의_일곱째_요청은_429_와_남은_초를_받는다() throws Exception {
        for (int i = 0; i < 6; i++) {
            when(clock.instant()).thenReturn(SERVING_NOW.plusSeconds(i));
            mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER)))).andExpect(status().isOk());
        }

        // 첫 요청이 10:15:30 이라 10:16:30 에 창을 벗어난다. 10:15:53.5 에서 남은 36.5초는 37로 올린다
        when(clock.instant()).thenReturn(SERVING_NOW.plusMillis(23_500));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("ADSERVING-003"));

        // 거부된 요청은 세지 않아서 알려 준 시각에 다시 오면 받는다
        when(clock.instant()).thenReturn(SERVING_NOW.plusSeconds(60));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:16:31+09:00"));
    }
}
