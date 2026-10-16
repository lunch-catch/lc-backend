package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;

class AnalyticsSecurityIntegrationTest extends AbstractAnalyticsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("analyticsFilterChain")
    private SecurityFilterChain analyticsFilterChain;

    @Autowired
    @Qualifier("memberFilterChain")
    private SecurityFilterChain memberFilterChain;

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/admin/dashboard/members",
            "/v1/admin/aggregation-runs",
            "/v1/admin/sales",
            "/v1/admin/sales/top-owners",
            "/v1/admin/fraud/invalid-impressions",
            "/v1/admin/campaign-reports"
    })
    void 관리자_GET_경로는_인증과_관리자_역할을_요구한다(String path) throws Exception {
        assertAdminGet(path);
    }

    @Test
    void 재집계_경로는_인증과_관리자_역할을_요구한다() throws Exception {
        mvc.perform(post("/v1/admin/aggregations:recalculate"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/admin/aggregations:recalculate").with(user("member").roles("MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/admin/aggregations:recalculate").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/admin/aggregations:recalculate").with(user("super").roles("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 회원_사용_통계_경로는_인증과_회원_역할을_요구한다() throws Exception {
        mvc.perform(get("/v1/members/me/usage-stats"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/members/me/usage-stats").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/members/me/usage-stats").with(user("member").roles("MEMBER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 이벤트_수집_경로는_인증과_회원_역할을_요구한다() throws Exception {
        mvc.perform(post("/v1/events"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/events").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/events").with(user("member").roles("MEMBER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 사용_통계_경로는_analytics_체인만_소유한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/members/me/usage-stats");

        assertThat(analyticsFilterChain.matches(request)).isTrue();
        assertThat(memberFilterChain.matches(request)).isFalse();
    }

    private void assertAdminGet(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("member").roles("MEMBER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).with(user("super").roles("SUPER_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
