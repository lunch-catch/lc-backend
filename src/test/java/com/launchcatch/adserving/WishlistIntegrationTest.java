package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.global.config.ClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/*
 * GET, POST, DELETE /v1/wishlist 를 HTTP 입구부터 MySQL 의 wishlist, ad_candidate 와 Valkey 의 당일 이력까지 본다.
 * 권한과 남의 찜을 돌려주지 않는 것이 이 테스트의 축이다. 찜한 시각 순서와 그날 후보 조인, 같은 찜의 두 번째 저장도 실제 DB 로 본다.
 */
@DisplayName("찜 목록 통합")
class WishlistIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final long OTHER_MEMBER_ID = 3002L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final String EXCLUSION_KEY = "adserving:exclusion:" + TODAY + ":" + MEMBER_ID;
    private static final Instant NOW = LocalDateTime.of(2026, 10, 1, 10, 30).atZone(ClockConfig.ZONE).toInstant();

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(clock).instant();
        doReturn(ClockConfig.ZONE).when(clock).getZone();
        candidate(TODAY, 101, 55, "점심식당", "https://cdn.example.com/stores/55/logo.jpg", "MENU", "AMOUNT", 3000, "갈비살 정식");
        candidate(TODAY, 102, 56, "저녁식당", null, "ALL", "PERCENT", 20, null);
        // 103 은 어제만 후보였다. 오늘은 노출 후보가 아니다
        candidate(TODAY.minusDays(1), 103, 57, "어제식당", null, "ALL", "PERCENT", 10, null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishlist");
        jdbcTemplate.update("DELETE FROM ad_candidate");
        redisTemplate.delete(EXCLUSION_KEY);
    }

    private void candidate(LocalDate date, long campaignId, long storeId, String storeName, String imageUrl,
                           String target, String type, int value, String menuName) {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, store_image_url, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, discount_menu_name, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'KOREAN', 37.4979, 127.0276, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    ?, ?, ?, ?, '12:00', '14:00', 30, NOW(6), 'SERVABLE', NOW(6), NOW(6))
                """, date, campaignId, storeId, storeName, imageUrl, target, type, value, menuName);
    }

    private void wish(long memberId, long campaignId, long storeId, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO wishlist (member_id, campaign_id, store_id, serve_id, created_at, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?)
                """, memberId, campaignId, storeId, createdAt, createdAt);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Role role) throws Exception {
        return mockMvc.perform(request.with(user(new CustomUserDetails(MEMBER_ID, role))));
    }

    @Test
    void 내_찜만_찜한_시각_내림차순으로_그날_후보의_표시_값과_함께_돌려준다() throws Exception {
        wish(MEMBER_ID, 102, 56, LocalDateTime.of(2026, 9, 30, 11, 0));
        wish(MEMBER_ID, 101, 55, LocalDateTime.of(2026, 10, 1, 10, 15, 34, 20_000_000));
        wish(OTHER_MEMBER_ID, 101, 55, LocalDateTime.of(2026, 10, 1, 10, 20));

        perform(get("/v1/wishlist"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.wishlist.length()").value(2))
                .andExpect(jsonPath("$.data.wishlist[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.wishlist[0].storeId").value(55))
                .andExpect(jsonPath("$.data.wishlist[0].storeName").value("점심식당"))
                .andExpect(jsonPath("$.data.wishlist[0].storeImageUrl").value("https://cdn.example.com/stores/55/logo.jpg"))
                .andExpect(jsonPath("$.data.wishlist[0].discount.target").value("MENU"))
                .andExpect(jsonPath("$.data.wishlist[0].discount.type").value("AMOUNT"))
                .andExpect(jsonPath("$.data.wishlist[0].discount.value").value(3000))
                .andExpect(jsonPath("$.data.wishlist[0].discount.menuName").value("갈비살 정식"))
                .andExpect(jsonPath("$.data.wishlist[0].wishedAt").value("2026-10-01T10:15:34.02+09:00"))
                .andExpect(jsonPath("$.data.wishlist[1].campaignId").value(102))
                .andExpect(jsonPath("$.data.wishlist[1].storeImageUrl").isEmpty())
                .andExpect(jsonPath("$.data.wishlist[1].discount.target").value("ALL"))
                .andExpect(jsonPath("$.data.wishlist[1].discount.menuName").isEmpty());
    }

    @Test
    void 오늘_노출_후보가_아닌_캠페인은_표시_값이_null_이다() throws Exception {
        wish(MEMBER_ID, 103, 57, LocalDateTime.of(2026, 9, 30, 11, 0));

        perform(get("/v1/wishlist"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wishlist[0].campaignId").value(103))
                .andExpect(jsonPath("$.data.wishlist[0].storeId").value(57))
                .andExpect(jsonPath("$.data.wishlist[0].wishedAt").value("2026-09-30T11:00:00+09:00"))
                .andExpect(jsonPath("$.data.wishlist[0].storeName").isEmpty())
                .andExpect(jsonPath("$.data.wishlist[0].storeImageUrl").isEmpty())
                .andExpect(jsonPath("$.data.wishlist[0].discount").isEmpty());
    }

    @Test
    void campaignId_를_주면_그_캠페인만_돌려준다() throws Exception {
        wish(MEMBER_ID, 101, 55, LocalDateTime.of(2026, 10, 1, 10, 0));
        wish(MEMBER_ID, 102, 56, LocalDateTime.of(2026, 10, 1, 10, 5));

        perform(get("/v1/wishlist").param("campaignId", "101"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wishlist.length()").value(1))
                .andExpect(jsonPath("$.data.wishlist[0].campaignId").value(101));
    }

    @Test
    void 남이_찜한_캠페인을_campaignId_로_물어도_빈_배열이다() throws Exception {
        wish(OTHER_MEMBER_ID, 101, 55, LocalDateTime.of(2026, 10, 1, 10, 0));

        perform(get("/v1/wishlist").param("campaignId", "101"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wishlist").isEmpty());
    }

    @Test
    void 찜이_없으면_200_과_빈_배열이다() throws Exception {
        perform(get("/v1/wishlist"), Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wishlist").isArray())
                .andExpect(jsonPath("$.data.wishlist").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이다(Role role) throws Exception {
        perform(get("/v1/wishlist"), role).andExpect(status().isForbidden());
    }

    @Test
    void 로그인하지_않으면_401_이다() throws Exception {
        mockMvc.perform(get("/v1/wishlist")).andExpect(status().isUnauthorized());
    }

    private ResultActions wish(String json, Role role) throws Exception {
        return perform(post("/v1/wishlist").contentType(MediaType.APPLICATION_JSON).content(json), role);
    }

    private long wishCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishlist", Long.class);
    }

    @Test
    void 가게_상세에서_처음_찜하면_201_이고_출처_카드_없이_남고_그날_당일_이력에_들어간다() throws Exception {
        wish("{\"campaignId\": 101}", Role.MEMBER)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(101))
                .andExpect(jsonPath("$.data.storeId").value(55))
                .andExpect(jsonPath("$.data.storeName").value("점심식당"))
                .andExpect(jsonPath("$.data.discount.menuName").value("갈비살 정식"))
                .andExpect(jsonPath("$.data.wishedAt").exists());

        assertThat(jdbcTemplate.queryForMap("SELECT member_id, campaign_id, store_id, serve_id FROM wishlist"))
                .containsEntry("member_id", MEMBER_ID).containsEntry("campaign_id", 101L)
                .containsEntry("store_id", 55L).containsEntry("serve_id", null);
        assertThat(redisTemplate.opsForSet().members(EXCLUSION_KEY)).containsExactly("101");
    }

    @Test
    void 이미_찜한_캠페인이면_200_이고_행이_늘지_않으며_처음_찜한_시각을_돌려준다() throws Exception {
        wish(MEMBER_ID, 101, 55, LocalDateTime.of(2026, 9, 30, 11, 0));

        wish("{\"campaignId\": 101}", Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaignId").value(101))
                .andExpect(jsonPath("$.data.wishedAt").value("2026-09-30T11:00:00+09:00"));

        assertThat(wishCount()).isEqualTo(1);
    }

    // 어제 찜했어도 오늘 후보가 아니면 거절한다(#420)
    @Test
    void 오늘_노출_후보가_아니면_404_ADSERVING_004_이고_찜이_늘지_않는다() throws Exception {
        wish(MEMBER_ID, 103, 57, LocalDateTime.of(2026, 9, 30, 11, 0));

        wish("{\"campaignId\": 103}", Role.MEMBER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADSERVING-004"));

        assertThat(wishCount()).isEqualTo(1);
        assertThat(redisTemplate.hasKey(EXCLUSION_KEY)).isFalse();
    }

    @Test
    void campaignId_가_없으면_400_COMMON_002_이다() throws Exception {
        wish("{}", Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_찜할_수_없다(Role role) throws Exception {
        wish("{\"campaignId\": 101}", role).andExpect(status().isForbidden());

        assertThat(wishCount()).isZero();
    }

    @Test
    void 로그인하지_않으면_찜할_수_없다() throws Exception {
        mockMvc.perform(post("/v1/wishlist").contentType(MediaType.APPLICATION_JSON).content("{\"campaignId\": 101}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 찜을_지우면_204_이고_내_그_찜만_사라지며_그날_당일_이력에_들어간다() throws Exception {
        wish(MEMBER_ID, 101, 55, LocalDateTime.of(2026, 9, 30, 11, 0));
        wish(MEMBER_ID, 102, 56, LocalDateTime.of(2026, 9, 30, 11, 5));
        wish(OTHER_MEMBER_ID, 101, 55, LocalDateTime.of(2026, 9, 30, 11, 10));

        perform(delete("/v1/wishlist/101"), Role.MEMBER)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(jdbcTemplate.queryForList("SELECT member_id, campaign_id FROM wishlist ORDER BY member_id, campaign_id"))
                .extracting(row -> row.get("member_id") + ":" + row.get("campaign_id"))
                .containsExactly(MEMBER_ID + ":102", OTHER_MEMBER_ID + ":101");
        assertThat(redisTemplate.opsForSet().members(EXCLUSION_KEY)).containsExactly("101");
    }

    // 찜 목록에 없어도 204 다(명세). 그날 피드에서는 뺀다(#547)
    @Test
    void 찜하지_않은_캠페인을_지워도_204_이고_그날_당일_이력에_들어간다() throws Exception {
        perform(delete("/v1/wishlist/999"), Role.MEMBER).andExpect(status().isNoContent());

        assertThat(wishCount()).isZero();
        assertThat(redisTemplate.opsForSet().members(EXCLUSION_KEY)).containsExactly("999");
    }

    // 공통 처리가 경로 변수의 타입 불일치를 COMMON-003 으로 돌려준다. api-spec.md 의 COMMON-002 행과 다르다(#547)
    @Test
    void 경로의_campaignId_가_숫자가_아니면_400_COMMON_003_이다() throws Exception {
        perform(delete("/v1/wishlist/abc"), Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-003"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_찜을_지울_수_없다(Role role) throws Exception {
        wish(MEMBER_ID, 101, 55, LocalDateTime.of(2026, 9, 30, 11, 0));

        perform(delete("/v1/wishlist/101"), role).andExpect(status().isForbidden());

        assertThat(wishCount()).isEqualTo(1);
    }

    @Test
    void 로그인하지_않으면_찜을_지울_수_없다() throws Exception {
        mockMvc.perform(delete("/v1/wishlist/101")).andExpect(status().isUnauthorized());
    }
}
