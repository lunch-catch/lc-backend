package com.launchcatch.analytics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/*
 * A-09, A-10 의 조회를 실제 MySQL 로 확인한다. 일별 집계 행을 직접 심고 HTTP 로 부른다. 쿼리의 기간 합산,
 * 필터, 정렬과 입력 거절이 대상이다. 입구는 HTTP 하나다.
 */
class FraudQueryIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 9, 10);
    private static final LocalDate DAY2 = LocalDate.of(2026, 9, 11);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM daily_invalid_analytics");
        jdbc.update("DELETE FROM daily_campaign_fraud_analytics");
    }

    private MockHttpServletRequestBuilder admin(String path) {
        return get(path).with(user(new CustomUserDetails(1L, Role.ADMIN)));
    }

    private void invalid(LocalDate date, long campaignId, long memberId, String reason, long count) {
        Timestamp now = Timestamp.valueOf(date.atTime(1, 0));
        jdbc.update("""
                INSERT INTO daily_invalid_analytics (business_date, reason_code, campaign_id, member_id, invalid_count,
                    created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Date.valueOf(date), reason, campaignId, memberId, count, now, now);
    }

    private void fraud(LocalDate date, long campaignId, long valid, long invalid, long distinct, long top,
            boolean suspected) {
        Timestamp now = Timestamp.valueOf(date.atTime(1, 0));
        jdbc.update("""
                INSERT INTO daily_campaign_fraud_analytics (business_date, campaign_id, valid_count, invalid_count,
                    distinct_member_count, top_members_count, concentration_suspected, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Date.valueOf(date), campaignId, valid, invalid, distinct, top, suspected, now, now);
    }

    @Test
    void 무효_노출은_기간_안에서_회원_캠페인_사유별로_합산하고_필터로_거른다() throws Exception {
        invalid(DAY1, 12, 55, "NOT_OWNER", 30);
        invalid(DAY2, 12, 55, "NOT_OWNER", 1);
        invalid(DAY1, 12, 55, "EXPIRED_OR_UNKNOWN", 4);
        invalid(DAY1, 13, 56, "EXPIRED_OR_UNKNOWN", 9);

        mvc.perform(admin("/v1/admin/fraud/invalid-impressions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString())
                        .param("memberId", "55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaryByReason[0].reasonCode").value("EXPIRED_OR_UNKNOWN"))
                .andExpect(jsonPath("$.data.summaryByReason[0].count").value(4))
                .andExpect(jsonPath("$.data.summaryByReason[1].count").value(31))
                .andExpect(jsonPath("$.data.items[0].reasonCode").value("NOT_OWNER"))
                .andExpect(jsonPath("$.data.items[0].count").value(31))
                .andExpect(jsonPath("$.data.items[1].count").value(4))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void 사유와_캠페인으로_거르고_정렬하고_쪽을_자른다() throws Exception {
        invalid(DAY1, 12, 55, "NOT_OWNER", 30);
        invalid(DAY1, 12, 56, "NOT_OWNER", 20);
        invalid(DAY1, 12, 57, "NOT_OWNER", 10);
        invalid(DAY1, 12, 55, "EXPIRED_OR_UNKNOWN", 99);
        invalid(DAY1, 13, 55, "NOT_OWNER", 77);

        mvc.perform(admin("/v1/admin/fraud/invalid-impressions")
                        .param("from", DAY1.toString()).param("to", DAY1.toString())
                        .param("reasonCode", "NOT_OWNER").param("campaignId", "12")
                        .param("sortBy", "memberId").param("sortDir", "asc")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summaryByReason.length()").value(1))
                .andExpect(jsonPath("$.data.summaryByReason[0].count").value(60))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].memberId").value(57))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void 캠페인_경고는_기간_합계로_비율을_재고_집중은_하루라도_걸리면_걸리며_점유율은_가장_높았던_날이다() throws Exception {
        fraud(DAY1, 12, 1_000, 0, 3, 600, true);
        fraud(DAY2, 12, 1_000, 300, 9, 100, false);

        mvc.perform(admin("/v1/admin/fraud/campaign-warnings")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].validCount").value(2_000))
                .andExpect(jsonPath("$.data.items[0].invalidCount").value(300))
                .andExpect(jsonPath("$.data.items[0].invalidRatio").value(0.15))
                .andExpect(jsonPath("$.data.items[0].invalidRatioWarning").value(false))
                .andExpect(jsonPath("$.data.items[0].concentrationWarning").value(true))
                .andExpect(jsonPath("$.data.items[0].topMembersShare").value(0.6))
                .andExpect(jsonPath("$.data.items[0].distinctMemberCount").value(3));

        mvc.perform(admin("/v1/admin/fraud/campaign-warnings")
                        .param("from", DAY1.toString()).param("to", DAY2.toString())
                        .param("type", "INVALID_RATIO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 기간을_빠뜨리거나_31일을_넘기면_ANALYTICS_001로_거절한다() throws Exception {
        mvc.perform(admin("/v1/admin/fraud/invalid-impressions").param("to", DAY2.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANALYTICS-001"));
        mvc.perform(admin("/v1/admin/fraud/campaign-warnings")
                        .param("from", DAY1.toString()).param("to", DAY1.plusDays(31).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANALYTICS-001"));
    }

    @Test
    void 잘못된_쪽_크기와_정렬과_사유는_400이다() throws Exception {
        mvc.perform(admin("/v1/admin/fraud/campaign-warnings")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin("/v1/admin/fraud/campaign-warnings")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("sortBy", "validCount"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin("/v1/admin/fraud/invalid-impressions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("reasonCode", "OTHER"))
                .andExpect(status().isBadRequest());
    }
}
