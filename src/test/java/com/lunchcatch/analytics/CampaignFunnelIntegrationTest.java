package com.lunchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.time.LocalDateTime;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CampaignFunnelIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long CAMPAIGN_ID = 509001;
    private static final long OWNER_ID = 509001;
    private static final String PATH = "/v1/owner/campaigns/" + CAMPAIGN_ID + "/analytics/funnel";
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("analyticsFilterChain")
    private SecurityFilterChain analyticsFilterChain;

    @Autowired
    @Qualifier("campaignFilterChain")
    private SecurityFilterChain campaignFilterChain;

    @BeforeEach
    void setUp() {
        jdbc.update("""
                INSERT INTO campaign
                    (campaign_id, owner_id, store_id, status, status_changed_at, discount_target_type,
                     discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time,
                     target_radius, target_gender, target_age_groups, daily_budget,
                     start_date, end_date, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, 'ALL', 'AMOUNT', 2000, 50, '11:00', '14:00',
                        1000, 'ALL', 'AGE_20S', 10000, '2026-10-01', '2026-10-31', ?, ?)
                """, CAMPAIGN_ID, OWNER_ID, OWNER_ID, NOON, NOON, NOON);
    }

    @Test
    void 요청_시_기록된_이벤트의_건수와_비율을_반환한다() throws Exception {
        insertEvents("impression", 10, CAMPAIGN_ID, NOON);
        insertEvents("wish", 2, CAMPAIGN_ID, NOON);
        insertEvents("pass", 6, CAMPAIGN_ID, NOON);
        insertEvents("issue", 1, CAMPAIGN_ID, NOON);
        insertEvents("redeem", 1, CAMPAIGN_ID, NOON);
        insertEvents("poster_open", 3, CAMPAIGN_ID, NOON);

        mvc.perform(request().with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.data.from").value("2026-10-01"))
                .andExpect(jsonPath("$.data.to").value("2026-10-01"))
                .andExpect(jsonPath("$.data.impressionCount").value(10))
                .andExpect(jsonPath("$.data.wishCount").value(2))
                .andExpect(jsonPath("$.data.passCount").value(6))
                .andExpect(jsonPath("$.data.issuedCount").value(1))
                .andExpect(jsonPath("$.data.redeemCount").value(1))
                .andExpect(jsonPath("$.data.wishRate").value(0.2))
                .andExpect(jsonPath("$.data.passRate").value(0.6))
                .andExpect(jsonPath("$.data.wishToIssueRate").value(0.5))
                .andExpect(jsonPath("$.data.issueToRedeemRate").value(1.0))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.aggregatedAt").doesNotExist());

        insertEvents("impression", 1, CAMPAIGN_ID, NOON);
        mvc.perform(request().with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.impressionCount").value(11));
    }

    @Test
    void 한국_날짜의_양끝을_포함하고_다른_날짜와_캠페인은_제외한다() throws Exception {
        LocalDateTime midnight = NOON.toLocalDate().atStartOfDay();
        insertEvents("impression", 1, CAMPAIGN_ID, midnight);
        insertEvents("impression", 1, CAMPAIGN_ID, midnight.plusDays(1).minusNanos(1000));
        insertEvents("impression", 1, CAMPAIGN_ID, midnight.minusNanos(1000));
        insertEvents("impression", 1, CAMPAIGN_ID, midnight.plusDays(1));
        insertEvents("impression", 1, CAMPAIGN_ID + 1, NOON);

        mvc.perform(request().with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.impressionCount").value(2))
                .andExpect(jsonPath("$.data.wishRate").value(0));
    }

    @Test
    void 기록이_없는_본인_캠페인은_건수_0과_null_비율을_반환한다() throws Exception {
        mvc.perform(request().with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.impressionCount").value(0))
                .andExpect(jsonPath("$.data.wishCount").value(0))
                .andExpect(jsonPath("$.data.passCount").value(0))
                .andExpect(jsonPath("$.data.issuedCount").value(0))
                .andExpect(jsonPath("$.data.redeemCount").value(0))
                .andExpect(jsonPath("$.data.wishRate").isEmpty())
                .andExpect(jsonPath("$.data.passRate").isEmpty())
                .andExpect(jsonPath("$.data.wishToIssueRate").isEmpty())
                .andExpect(jsonPath("$.data.issueToRedeemRate").isEmpty());
    }

    @Test
    void 남의_캠페인과_없는_캠페인은_동일한_403을_반환한다() throws Exception {
        String forbidden = mvc.perform(request().with(user(new CustomUserDetails(OWNER_ID + 1, Role.OWNER))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get("/v1/owner/campaigns/{campaignId}/analytics/funnel", CAMPAIGN_ID + 1)
                        .param("from", "2026-10-01").param("to", "2026-10-01")
                        .with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

        assertThat(missing).isEqualTo(forbidden);
    }

    @Test
    void 인증하지_않으면_401이고_다른_역할이면_403이다() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
        mvc.perform(request().with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(request().with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({
            "?from=2026-10-01, COMMON-003",
            "?from=invalid&to=2026-10-01, COMMON-003",
            "?from=2026-10-02&to=2026-10-01, ANALYTICS-001",
            "?from=2026-10-01&to=2026-11-01, ANALYTICS-001"
    })
    void 필수값과_날짜_형식과_기간_한도를_검사한다(String query, String code) throws Exception {
        mvc.perform(get(PATH + query).with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void 캠페인_ID는_양수여야_한다() throws Exception {
        mvc.perform(get("/v1/owner/campaigns/0/analytics/funnel")
                        .param("from", "2026-10-01").param("to", "2026-10-01")
                        .with(user(new CustomUserDetails(OWNER_ID, Role.OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 성과_조회는_analytics_보안_체인만_소유한다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);

        assertThat(analyticsFilterChain.matches(request)).isTrue();
        assertThat(campaignFilterChain.matches(request)).isFalse();
    }

    @Test
    void 캠페인별_조회_인덱스가_적용된다() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'event_log'
                  AND index_name = 'idx_event_log_campaign_occurred_type'
                ORDER BY seq_in_index
                """, String.class)).containsExactly("campaign_id", "occurred_at", "event_type");
    }

    private MockHttpServletRequestBuilder request() {
        return get(PATH).param("from", "2026-10-01").param("to", "2026-10-01");
    }

    private void insertEvents(String type, int count, long campaignId, LocalDateTime occurredAt) {
        jdbc.batchUpdate("""
                INSERT INTO event_log
                    (event_id, event_type, member_id, campaign_id, store_id, wish_id,
                     coupon_event_id, member_coupon_id, occurred_at, created_at, updated_at)
                VALUES (UUID(), ?, 1, ?, 1, CASE WHEN ? = 'wish' THEN 1 END,
                        CASE WHEN ? = 'issue' THEN 1 END,
                        CASE WHEN ? IN ('issue', 'redeem') THEN 1 END, ?, ?, ?)
                """, IntStream.range(0, count).mapToObj(index -> new Object[] {
                        type, campaignId, type, type, type, occurredAt, occurredAt, occurredAt
                }).toList());
    }
}
