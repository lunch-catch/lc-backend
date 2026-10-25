package com.launchcatch.analytics;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class OwnerReportIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long OWNER_ID = 492901;
    private static final long OTHER_OWNER_ID = 492902;
    private static final long CAMPAIGN_ID = 492801;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 16);
    private static final LocalDate TO = LocalDate.of(2026, 10, 17);
    private static final CustomUserDetails OWNER = new CustomUserDetails(OWNER_ID, Role.OWNER);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 본인과_기간으로_거르고_날짜순으로_정렬한_집계와_합계를_반환한다() throws Exception {
        insert(CAMPAIGN_ID, OWNER_ID, TO, 8000, 4700, 200);
        insert(CAMPAIGN_ID, OWNER_ID, FROM, 7000, 4000, 100);
        insert(CAMPAIGN_ID, OWNER_ID, FROM.minusDays(1), 7000, 9999, 0);
        insert(CAMPAIGN_ID, OWNER_ID, TO.plusDays(1), 7000, 9999, 0);
        insert(492802, OTHER_OWNER_ID, FROM, 7000, 9999, 0);
        insert(492803, null, FROM, 7000, 9999, 0);

        mvc.perform(get("/v1/owner/report")
                        .with(user(OWNER))
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("ownerId", Long.toString(OTHER_OWNER_ID)))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "code": "SUCCESS",
                          "message": "요청이 성공적으로 처리되었습니다.",
                          "data": {
                            "from": "2026-10-16", "to": "2026-10-17", "totalSpentAmount": 8400,
                            "items": [
                              {
                                "businessDate": "2026-10-16", "campaignId": 492801,
                                "dailyBudget": 7000, "impressionUnitCost": 2,
                                "validImpressionCount": 2400, "invalidImpressionCount": 8,
                                "wishCount": 180, "issuedCount": 190, "redeemCount": 76,
                                "chargedAmount": 4000, "refundedAmount": 100, "spentAmount": 3900
                              },
                              {
                                "businessDate": "2026-10-17", "campaignId": 492801,
                                "dailyBudget": 8000, "impressionUnitCost": 2,
                                "validImpressionCount": 2400, "invalidImpressionCount": 8,
                                "wishCount": 180, "issuedCount": 190, "redeemCount": 76,
                                "chargedAmount": 4700, "refundedAmount": 200, "spentAmount": 4500
                              }
                            ]
                          }
                        }
                        """))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].businessDate").value(FROM.toString()))
                .andExpect(jsonPath("$.data.items[1].businessDate").value(TO.toString()));
    }

    @Test
    void 하루에_여러_캠페인이_있으면_ID순으로_모두_반환한다() throws Exception {
        insert(492802, OWNER_ID, FROM, 7000, 200, 0);
        insert(CAMPAIGN_ID, OWNER_ID, FROM, 7000, 100, 0);

        mvc.perform(get("/v1/owner/report").with(user(OWNER))
                        .param("from", FROM.toString()).param("to", FROM.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSpentAmount").value(300))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].campaignId").value(CAMPAIGN_ID))
                .andExpect(jsonPath("$.data.items[1].campaignId").value(492802));
    }

    // 명세는 아직 집계되지 않은 날의 항목을 만들지 않는다. 0 으로 내리면 화면이 오늘을 "집계 중" 이 아니라 0 으로 그린다(#895)
    @Test
    void 집계_중인_날의_행은_항목으로_돌려주지_않는다() throws Exception {
        insert(CAMPAIGN_ID, OWNER_ID, FROM, 7000, 4000, 100);
        jdbc.update("""
                INSERT INTO campaign_daily_report
                    (campaign_id, owner_id, business_date, daily_budget, impression_unit_cost, report_status,
                     finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, 7000, 2, 'AGGREGATING', NULL, NOW(6), NOW(6))
                """, CAMPAIGN_ID, OWNER_ID, TO);

        mvc.perform(get("/v1/owner/report").with(user(OWNER))
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSpentAmount").value(3900))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].businessDate").value(FROM.toString()));
    }

    @Test
    void 본인의_집계가_없으면_다른_점주의_집계가_있어도_빈_결과다() throws Exception {
        insert(492802, OTHER_OWNER_ID, FROM, 7000, 9999, 0);

        mvc.perform(get("/v1/owner/report").with(user(OWNER))
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalSpentAmount").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mvc.perform(get("/v1/owner/report")
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "ADMIN", "SUPER_ADMIN"})
    void 점주_이외의_역할은_403이다(String role) throws Exception {
        mvc.perform(get("/v1/owner/report").with(user("other").roles(role))
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @ParameterizedTest
    @CsvSource({"2026-10-17, 2026-10-16", "2026-09-17, 2026-10-17"})
    void 역전된_기간과_31일_범위는_400이다(String from, String to) throws Exception {
        mvc.perform(get("/v1/owner/report").with(user(OWNER))
                        .param("from", from).param("to", to))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANALYTICS-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from=2026-10-16", "to=2026-10-17", "from=invalid&to=2026-10-17",
            "from=2026-02-30&to=2026-10-17"})
    void 날짜가_누락되거나_날짜_형식이_아니면_400이다(String query) throws Exception {
        mvc.perform(get("/v1/owner/report?" + query).with(user(OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-003"));
    }

    private void insert(long campaignId, Long ownerId, LocalDate date, long budget, long charged, long refunded) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 18, 1, 0);
        jdbc.update("""
                INSERT INTO campaign_daily_report
                    (campaign_id, owner_id, business_date, daily_budget, impression_unit_cost,
                     valid_impression_count, billable_impression_count, invalid_impression_count,
                     wish_count, issued_count, redeem_count, charged_amount, refunded_amount, spent_amount,
                     report_status, finalized_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 2, 2400, 2400, 8, 180, 190, 76, ?, ?, ?, 'FINALIZED', ?, ?, ?)
                """, campaignId, ownerId, date, budget, charged, refunded, charged - refunded, now, now, now);
    }
}
