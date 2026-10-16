package com.launchcatch.adserving;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.ResultActions;

/*
 * GET /v1/feed-filter 를 HTTP 입구부터 MySQL 의 feed_filter, feed_filter_category 까지 본다.
 * 권한이 이 테스트의 축이다. 쿼리의 업종 순서도 실제 DB 로 본다.
 */
@DisplayName("피드 필터 조회 통합")
class FeedFilterIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM feed_filter_category");
        jdbcTemplate.update("DELETE FROM feed_filter");
    }

    private ResultActions get(Role role) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/feed-filter")
                .with(user(new CustomUserDetails(MEMBER_ID, role))));
    }

    @Test
    void 저장한_업종을_코드_오름차순으로_정렬과_함께_돌려준다() throws Exception {
        jdbcTemplate.update("INSERT INTO feed_filter (member_id, sort_type, created_at, updated_at) VALUES (?, 'DISCOUNT_RATE', NOW(6), NOW(6))", MEMBER_ID);
        for (String code : new String[]{"KOREAN", "CAFE_DESSERT", "JAPANESE"}) {
            jdbcTemplate.update("INSERT INTO feed_filter_category (member_id, category_code, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))", MEMBER_ID, code);
        }

        get(Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.categories[0]").value("CAFE_DESSERT"))
                .andExpect(jsonPath("$.data.categories[1]").value("JAPANESE"))
                .andExpect(jsonPath("$.data.categories[2]").value("KOREAN"))
                .andExpect(jsonPath("$.data.sort").value("DISCOUNT_RATE"));
    }

    @Test
    void 저장한_적이_없으면_빈_배열과_거리순이다() throws Exception {
        get(Role.MEMBER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isEmpty())
                .andExpect(jsonPath("$.data.sort").value("DISTANCE"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이다(Role role) throws Exception {
        get(role).andExpect(status().isForbidden());
    }

    @Test
    void 로그인하지_않으면_401_이다() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/feed-filter"))
                .andExpect(status().isUnauthorized());
    }
}
