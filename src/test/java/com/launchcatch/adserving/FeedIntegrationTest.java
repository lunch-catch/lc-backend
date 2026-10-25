package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.SlotLabels;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/*
 * GET /v1/feed 를 HTTP 입구부터 MySQL 의 ad_candidate 와 Valkey 의 카운터까지 한 번에 본다.
 * 권한과 노출 상한(돈)이 이 테스트의 축이다. 요청 제한과 하루 횟수는 그 축이 아니지만 Lua 스크립트가 단위 테스트에서 돌지 않아 여기서 함께 돌린다.
 */
@DisplayName("피드 조회 통합")
class FeedIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant SERVING_NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 30)
            .atZone(ClockConfig.ZONE).toInstant();
    private static final double LAT = 37.4979;
    private static final double LNG = 127.0276;

    @Autowired ServeRecordRepository serveRecordRepository;

    @BeforeEach
    void setUp() {
        doReturn(SERVING_NOW).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
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
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        jdbcTemplate.update("DELETE FROM feed_filter_category");
        jdbcTemplate.update("DELETE FROM feed_filter");
        java.util.Set<String> serveKeys = redisTemplate.keys("adserving:serve:*");
        if (serveKeys != null && !serveKeys.isEmpty()) {
            redisTemplate.delete(serveKeys);
        }
        redisTemplate.delete(List.of("adserving:feedRequests:" + MEMBER_ID,
                "adserving:servedDaily:" + TODAY + ":" + MEMBER_ID, "adserving:servedSession:" + MEMBER_ID,
                "adserving:validImpressions:" + TODAY));
    }

    private void at(LocalDateTime time) {
        doReturn(time.atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();
    }

    private void insertCandidate(long campaignId, String state, LocalDateTime soldOutAt) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, sold_out_at, reserved_amount, unit_price,
                    created_at, updated_at)
                VALUES (?, ?, 55, '점심식당', 'KOREAN', ?, ?, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), ?, ?, 100000, 10, NOW(6), NOW(6))
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

    @Test
    void 저장한_업종_필터가_다음_피드_요청에_반영된다() throws Exception {
        mockMvc.perform(put("/v1/feed-filter").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"JAPANESE\"],\"sort\":\"DISTANCE\"}")
                        .with(user(as(Role.MEMBER))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards").isEmpty());
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
            doReturn(SERVING_NOW.plusSeconds(i)).when(clock).instant();
            mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER)))).andExpect(status().isOk());
        }

        // 첫 요청이 10:15:30 이라 10:16:30 에 창을 벗어난다. 10:15:53.5 에서 남은 36.5초는 37로 올린다
        doReturn(SERVING_NOW.plusMillis(23_500)).when(clock).instant();
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.code").value("ADSERVING-003"));

        // 거부된 요청은 세지 않아서 알려 준 시각에 다시 오면 받는다
        doReturn(SERVING_NOW.plusSeconds(60)).when(clock).instant();
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-01T10:16:31+09:00"));
    }

    @Test
    void 그날_유효_노출_수가_노출_상한에_닿은_캠페인은_내려주지_않는다() throws Exception {
        // 하루 끝 직전이라 진행률(약 1.05)이 아니라 노출 상한으로 가른다
        at(LocalDateTime.of(2026, 10, 1, 23, 59));
        redisTemplate.opsForHash().put("adserving:validImpressions:" + TODAY, "101", "10500");

        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));
    }

    @Test
    void 상한_바로_아래면_내려준다() throws Exception {
        at(LocalDateTime.of(2026, 10, 1, 23, 59));
        redisTemplate.opsForHash().put("adserving:validImpressions:" + TODAY, "101", "10499");

        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));
    }

    @Test
    void 같은_캠페인은_한_세션에_한_번_하루에_두_번까지만_내려준다() throws Exception {
        at(LocalDateTime.of(2026, 10, 1, 10, 15));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));
        assertThat(redisTemplate.getExpire("adserving:servedSession:" + MEMBER_ID)).isBetween(1L, 1800L);

        // 30분이 지나지 않아 같은 세션이다
        at(LocalDateTime.of(2026, 10, 1, 10, 20));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));

        // 세션이 끝난 것처럼 키를 지운다. TTL 은 Valkey 의 실제 시계로 흘러 테스트의 시계로 당길 수 없다
        redisTemplate.delete("adserving:servedSession:" + MEMBER_ID);
        at(LocalDateTime.of(2026, 10, 1, 10, 55));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));

        redisTemplate.delete("adserving:servedSession:" + MEMBER_ID);
        at(LocalDateTime.of(2026, 10, 1, 11, 30));
        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));
    }

    @Test
    void 예약액을_아직_읽지_못한_후보가_있으면_예산_규칙_없이_서빙을_이어가고_정산을_부르지_않는다() throws Exception {
        jdbcTemplate.update("UPDATE ad_candidate SET reserved_amount = NULL, unit_price = NULL WHERE campaign_id = 101");
        redisTemplate.opsForHash().put("adserving:validImpressions:" + TODAY, "101", "20000");

        mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));
        verifyNoInteractions(budgetQueryService);
    }

    // 서빙 로그는 응답 뒤 대기열이 쓴다. 쓰일 때까지 잠깐 기다린다
    private long countEventually(String sql, long expected) throws InterruptedException {
        long count = 0;
        for (int i = 0; i < 50; i++) {
            count = jdbcTemplate.queryForObject(sql, Long.class);
            if (count >= expected) {
                return count;
            }
            Thread.sleep(100);
        }
        return count;
    }

    @Test
    void 응답한_카드의_serve_기록을_serveId_로_찾아_노출을_검증할_수_있다() throws Exception {
        String body = mockMvc.perform(get("/v1/feed").with(user(as(Role.MEMBER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.UUID serveId = java.util.UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(body, "$.data.cards[0].serveId"));

        assertThat(serveRecordRepository.findById(serveId)).contains(new ServeRecord(
                serveId, MEMBER_ID, 101L, 55L, SlotLabels.ALLOCATION, 1, SERVING_NOW));
        assertThat(redisTemplate.getExpire("adserving:serve:" + serveId)).isBetween(1L, 1800L);
    }

    @Test
    void 응답_뒤_serve_log_와_그날_첫_접속_위치를_쓴다() throws Exception {
        String body = mockMvc.perform(get("/v1/feed").param("latitude", "37.49794").param("longitude", "127.02765")
                        .with(user(as(Role.MEMBER))))
                .andReturn().getResponse().getContentAsString();
        String serveId = com.jayway.jsonpath.JsonPath.read(body, "$.data.cards[0].serveId");
        String feedId = com.jayway.jsonpath.JsonPath.read(body, "$.data.feedId");

        assertThat(countEventually("SELECT COUNT(*) FROM serve_log", 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT BIN_TO_UUID(serve_id) AS serve_id, BIN_TO_UUID(feed_id) AS feed_id, business_date, member_id,
                       campaign_id, store_id, slot_type, slot_position
                FROM serve_log
                """)).containsEntry("serve_id", serveId).containsEntry("feed_id", feedId)
                .containsEntry("member_id", MEMBER_ID).containsEntry("campaign_id", 101L)
                .containsEntry("store_id", 55L).containsEntry("slot_type", "ALLOCATION");
        // 배정 근거도 남는다. 노출이 없어 진행률은 0 이다
        Map<String, Object> reasons = jdbcTemplate.queryForMap(
                "SELECT progress_rate, relevance_score, engine_version FROM serve_log");
        assertThat((BigDecimal) reasons.get("progress_rate")).isEqualByComparingTo("0");
        assertThat((BigDecimal) reasons.get("relevance_score")).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(reasons).containsEntry("engine_version", FeedAssembler.ENGINE_VERSION);
        assertThat(countEventually("SELECT COUNT(*) FROM feed_visit_daily", 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT member_id, lat_rounded, lng_rounded FROM feed_visit_daily"))
                .containsEntry("lat_rounded", new BigDecimal("37.498"))
                .containsEntry("lng_rounded", new BigDecimal("127.028"));
    }

    @Test
    void 그날_두_번째_요청의_위치는_남기지_않는다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", "37.5").param("longitude", "127.0").with(user(as(Role.MEMBER))));
        countEventually("SELECT COUNT(*) FROM feed_visit_daily", 1);
        at(LocalDateTime.of(2026, 10, 1, 10, 20));
        mockMvc.perform(get("/v1/feed").param("latitude", "37.6").param("longitude", "127.1").with(user(as(Role.MEMBER))));
        Thread.sleep(500);

        assertThat(jdbcTemplate.queryForMap("SELECT lat_rounded FROM feed_visit_daily"))
                .containsEntry("lat_rounded", new BigDecimal("37.500"));
    }
}
