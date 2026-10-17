package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/*
 * GET, PUT /v1/feed-filter 를 HTTP 입구부터 MySQL 의 feed_filter, feed_filter_category 까지 본다.
 * 권한이 이 테스트의 축이다. 쿼리의 업종 순서와 저장의 upsert, 지우고 다시 넣기도 실제 DB 로 본다.
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

    private ResultActions put(Role role, String json) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/feed-filter")
                .contentType(MediaType.APPLICATION_JSON).content(json)
                .with(user(new CustomUserDetails(MEMBER_ID, role))));
    }

    private String savedSort() {
        return jdbcTemplate.queryForObject("SELECT sort_type FROM feed_filter WHERE member_id = ?", String.class, MEMBER_ID);
    }

    private List<String> savedCategories() {
        return jdbcTemplate.queryForList(
                "SELECT category_code FROM feed_filter_category WHERE member_id = ? ORDER BY category_code", String.class, MEMBER_ID);
    }

    @Test
    void 저장하면_업종을_코드_오름차순으로_돌려주고_조회에도_반영된다() throws Exception {
        put(Role.MEMBER, "{\"categories\":[\"KOREAN\",\"JAPANESE\"],\"sort\":\"DISCOUNT_RATE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.categories[0]").value("JAPANESE"))
                .andExpect(jsonPath("$.data.categories[1]").value("KOREAN"))
                .andExpect(jsonPath("$.data.sort").value("DISCOUNT_RATE"));

        assertThat(savedSort()).isEqualTo("DISCOUNT_RATE");
        assertThat(savedCategories()).containsExactly("JAPANESE", "KOREAN");
        get(Role.MEMBER)
                .andExpect(jsonPath("$.data.categories[0]").value("JAPANESE"))
                .andExpect(jsonPath("$.data.categories[1]").value("KOREAN"))
                .andExpect(jsonPath("$.data.sort").value("DISCOUNT_RATE"));
    }

    @Test
    void 이미_저장한_필터를_통째로_바꾼다() throws Exception {
        put(Role.MEMBER, "{\"categories\":[\"KOREAN\",\"JAPANESE\"],\"sort\":\"DISCOUNT_RATE\"}");

        put(Role.MEMBER, "{\"categories\":[\"CHINESE\"],\"sort\":\"DISTANCE\"}").andExpect(status().isOk());

        assertThat(savedSort()).isEqualTo("DISTANCE");
        assertThat(savedCategories()).containsExactly("CHINESE");
    }

    @Test
    void 빈_배열이면_업종_행을_모두_지운다() throws Exception {
        put(Role.MEMBER, "{\"categories\":[\"KOREAN\"],\"sort\":\"DISTANCE\"}");

        put(Role.MEMBER, "{\"categories\":[],\"sort\":\"DISTANCE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isEmpty());

        assertThat(savedCategories()).isEmpty();
    }

    @Test
    void 같은_요청을_두_번_보내도_결과가_같다() throws Exception {
        String json = "{\"categories\":[\"KOREAN\",\"JAPANESE\"],\"sort\":\"DISCOUNT_RATE\"}";
        put(Role.MEMBER, json).andExpect(status().isOk());

        put(Role.MEMBER, json).andExpect(status().isOk());

        assertThat(savedSort()).isEqualTo("DISCOUNT_RATE");
        assertThat(savedCategories()).containsExactly("JAPANESE", "KOREAN");
    }

    // 중복을 거부하지 않고 합치는 것은 #411 에서 임시로 정했다
    @Test
    void 임시_같은_업종이_두_번_오면_하나로_저장한다() throws Exception {
        put(Role.MEMBER, "{\"categories\":[\"KOREAN\",\"KOREAN\"],\"sort\":\"DISTANCE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories.length()").value(1));

        assertThat(savedCategories()).containsExactly("KOREAN");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"categories\":[\"PIZZA\"],\"sort\":\"DISTANCE\"}",
            "{\"categories\":[\"korean\"],\"sort\":\"DISTANCE\"}",
            "{\"categories\":[null],\"sort\":\"DISTANCE\"}",
            "{\"categories\":[\"KOREAN\"],\"sort\":\"POPULAR\"}",
            "{\"sort\":\"DISTANCE\"}",
            "{\"categories\":[\"KOREAN\"]}"
    })
    void 없는_업종_코드나_허용되지_않은_정렬이면_COMMON_002_이고_저장하지_않는다(String json) throws Exception {
        put(Role.MEMBER, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feed_filter", Long.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void 저장도_MEMBER_가_아니면_403_이고_저장하지_않는다(Role role) throws Exception {
        put(role, "{\"categories\":[],\"sort\":\"DISTANCE\"}").andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM feed_filter", Long.class)).isZero();
    }
}
