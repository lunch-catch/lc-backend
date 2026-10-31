package com.lunchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.adserving.contract.SwipeAction;
import com.lunchcatch.adserving.contract.SwipedEvent;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.member.contract.AgeGroup;
import com.lunchcatch.member.contract.Gender;
import com.lunchcatch.member.contract.MemberInfo;
import com.lunchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/*
 * POST /v1/swipes 를 HTTP 입구부터 MySQL 의 swipe_log, wishlist 와 Valkey 의 당일 이력까지 본다.
 * 권한이 이 테스트의 축이다. 찜과 패스한 캠페인이 그날 피드에서 빠지는지도 피드까지 이어 본다.
 */
@DisplayName("스와이프 통합")
class SwipeIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 35).atZone(ClockConfig.ZONE).toInstant();

    private final UUID serveId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, reserved_amount, unit_price, created_at, updated_at)
                VALUES (?, 101, 55, '점심식당', 'KOREAN', 37.4979, 127.0276, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), 'SERVABLE', 100000, 10, NOW(6), NOW(6))
                """, TODAY);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM swipe_log");
        jdbcTemplate.update("DELETE FROM wishlist");
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        redisTemplate.delete(List.of("adserving:exclusion:" + TODAY + ":" + MEMBER_ID,
                "adserving:feedRequests:" + MEMBER_ID, "adserving:servedDaily:" + TODAY + ":" + MEMBER_ID,
                "adserving:servedSession:" + MEMBER_ID));
    }

    private String body(long campaignId, String action) {
        return """
                {"serveId": "%s", "campaignId": %d, "action": "%s", "occurredAt": "2026-10-01T10:15:34.020+09:00"}
                """.formatted(serveId, campaignId, action);
    }

    private ResultActions send(String json, Role role) throws Exception {
        return mockMvc.perform(post("/v1/swipes").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(user(new CustomUserDetails(MEMBER_ID, role))));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void 찜하면_스와이프와_찜이_남고_그날_당일_이력에_들어간다() throws Exception {
        send(body(101, "WISH"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.applied").value(true));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT BIN_TO_UUID(serve_id) AS serve_id, member_id, business_date, campaign_id, category_code, action
                FROM swipe_log
                """)).containsEntry("serve_id", serveId.toString()).containsEntry("member_id", MEMBER_ID)
                .containsEntry("campaign_id", 101L).containsEntry("category_code", "KOREAN")
                .containsEntry("action", "WISH");
        assertThat(jdbcTemplate.queryForMap("SELECT member_id, campaign_id, store_id, BIN_TO_UUID(serve_id) AS serve_id FROM wishlist"))
                .containsEntry("member_id", MEMBER_ID).containsEntry("campaign_id", 101L)
                .containsEntry("store_id", 55L).containsEntry("serve_id", serveId.toString());
        assertThat(redisTemplate.opsForSet().members("adserving:exclusion:" + TODAY + ":" + MEMBER_ID))
                .containsExactly("101");
        assertThat(redisTemplate.getExpire("adserving:exclusion:" + TODAY + ":" + MEMBER_ID)).isPositive();
    }

    @Test
    void 패스하면_찜에_들어가지_않는다() throws Exception {
        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        assertThat(count("swipe_log")).isEqualTo(1);
        assertThat(count("wishlist")).isZero();
    }

    @Test
    void 같은_카드를_다시_넘기면_applied_false_이고_기록이_늘지_않는다() throws Exception {
        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        send(body(101, "WISH"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));

        assertThat(count("swipe_log")).isEqualTo(1);
        assertThat(count("wishlist")).isZero();
    }

    @Test
    void 찜하면_그_찜의_id_가_실린_찜_이벤트가_넘어간다() throws Exception {
        send(body(101, "WISH"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        Long wishId = jdbcTemplate.queryForObject("SELECT wishlist_id FROM wishlist", Long.class);
        assertThat(funnelEvents.received(SwipedEvent.class)).containsExactly(new SwipedEvent(
                "swipe:" + serveId + ":" + MEMBER_ID, LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000), MEMBER_ID,
                101L, 55L, serveId, SwipeAction.WISH, wishId));
    }

    @Test
    void 가게_상세에서_먼저_찜한_캠페인을_찜하면_그_찜의_id_가_실린다() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
                VALUES (?, 101, 55, NULL, NOW(6), NOW(6))
                """, MEMBER_ID);
        Long wishId = jdbcTemplate.queryForObject("SELECT wishlist_id FROM wishlist", Long.class);

        send(body(101, "WISH"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        assertThat(funnelEvents.received(SwipedEvent.class)).singleElement()
                .extracting(SwipedEvent::wishId).isEqualTo(wishId);
    }

    @Test
    void 패스하면_찜_id_없이_패스_이벤트가_넘어간다() throws Exception {
        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        assertThat(funnelEvents.received(SwipedEvent.class)).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo(SwipeAction.PASS);
            assertThat(event.wishId()).isNull();
        });
    }

    @Test
    void 같은_카드를_다시_넘기면_이벤트가_다시_넘어가지_않는다() throws Exception {
        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));
        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(false));

        assertThat(funnelEvents.received(SwipedEvent.class)).hasSize(1);
    }

    @Test
    void 찜이나_패스한_캠페인은_그날_피드에_다시_나오지_않는다() throws Exception {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(MEMBER_ID, "점심러",
                MemberStatus.ACTIVE, true, true, true, BigDecimal.valueOf(37.4979), BigDecimal.valueOf(127.0276),
                Gender.FEMALE, AgeGroup.AGE_30S)));

        send(body(101, "PASS"), Role.MEMBER).andExpect(jsonPath("$.data.applied").value(true));

        mockMvc.perform(get("/v1/feed").with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));
    }

    @Test
    void 오늘_후보에_없는_캠페인이면_404_ADSERVING_004_이고_남기지_않는다() throws Exception {
        send(body(999, "WISH"), Role.MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADSERVING-004"));

        assertThat(count("swipe_log")).isZero();
        assertThat(count("wishlist")).isZero();
    }

    @Test
    void action_값이_틀리거나_필수_필드가_빠지면_COMMON_002_다() throws Exception {
        send(body(101, "JUMP"), Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
        send("{\"serveId\": \"" + serveId + "\", \"campaignId\": 101}", Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이고_남기지_않는다(Role role) throws Exception {
        send(body(101, "WISH"), role).andExpect(status().isForbidden());

        assertThat(count("swipe_log")).isZero();
    }
}
