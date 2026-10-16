package com.launchcatch.adserving;

import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/*
 * GET /v1/wishlist 를 HTTP 입구부터 MySQL 의 wishlist, ad_candidate 까지 본다.
 * 권한과 남의 찜을 돌려주지 않는 것이 이 테스트의 축이다. 찜한 시각 순서와 그날 후보 조인도 실제 DB 로 본다.
 */
@DisplayName("찜 목록 조회 통합")
class WishlistIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final long OTHER_MEMBER_ID = 3002L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
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
}
