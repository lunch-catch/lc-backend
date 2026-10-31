package com.lunchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.analytics.batch.job.DailyAggregationJob;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.config.ClockConfig;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/*
 * 회원 사용 통계(A-12)가 일별 집계에서 조회까지 이어지는지를 실제 MySQL 로 끝까지 확인한다. 찜 수를 event_log
 * 에서 영업일과 회원 경계대로 세는 직접 쓴 SQL 과 그날 행을 지우고 넣는 교체, 13개월보다 오래된 행까지 합산하는
 * 조회 SQL 을 한 번은 실제로 돌려 봐야 해서 둔다. 입구는 시각(배치)과 HTTP(GET /v1/members/me/usage-stats)
 * 둘이다.
 *
 * 사용 수와 할인액의 원천인 쿠폰 확정 기록은 이 테스트가 심지 않는다. 그 값을 옮기는 일은 MemberUsageStepTest
 * 가 덮는다. 다른 테스트가 남긴 쿠폰 행이 같은 DB 에 있을 수 있어 이 테스트의 회원만 단정한다.
 */
class MemberUsageIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long CAMPAIGN = 973_100L;
    private static final long STORE = 973_200L;
    private static final long WISHER = 973_300L;
    private static final long OTHER_WISHER = 973_301L;
    private static final long NO_ROWS = 973_302L;
    private static final long OLD_ROWS = 973_303L;

    private final LocalDate today = LocalDate.now(ClockConfig.ZONE);
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate twoDaysAgo = today.minusDays(2);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private DailyAggregationJob job;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log");
        jdbc.update("DELETE FROM aggregation_pending_date");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM daily_member_usage WHERE member_id BETWEEN ? AND ?", WISHER, OLD_ROWS);
        jdbc.update("DELETE FROM event_log WHERE campaign_id = ?", CAMPAIGN);
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
    }

    @Test
    void 찜_수는_회원과_영업일_경계대로_세어_저장되고_본인_행의_합만_조회된다() throws Exception {
        wish(WISHER, yesterday.atStartOfDay());
        wish(WISHER, yesterday.atTime(23, 59, 59, 999_999_000));
        wish(WISHER, today.atStartOfDay());
        wish(WISHER, yesterday.atStartOfDay().minusNanos(1_000));
        wish(OTHER_WISHER, yesterday.atTime(12, 0));
        event("pass", WISHER, yesterday.atTime(12, 0));

        job.run();

        assertThat(wishCountOf(WISHER, yesterday)).isEqualTo(2);
        assertThat(wishCountOf(WISHER, twoDaysAgo)).isEqualTo(1);
        assertThat(wishCountOf(OTHER_WISHER, yesterday)).isEqualTo(1);
        assertUsageStats(WISHER).andExpect(jsonPath("$.data.wishCount").value(3));
        assertUsageStats(OTHER_WISHER).andExpect(jsonPath("$.data.wishCount").value(1));
    }

    @Test
    void 사용도_찜도_없는_회원은_행이_없고_조회하면_세_값이_0이다() throws Exception {
        wish(WISHER, yesterday.atTime(9, 0));

        job.run();

        assertThat(count("SELECT COUNT(*) FROM daily_member_usage WHERE member_id = ?", NO_ROWS)).isZero();
        assertUsageStats(NO_ROWS)
                .andExpect(jsonPath("$.data.wishCount").value(0))
                .andExpect(jsonPath("$.data.redeemCount").value(0))
                .andExpect(jsonPath("$.data.savedAmount").value(0));
    }

    @Test
    void 같은_날짜를_다시_집계해도_합이_두_배가_되지_않고_사라진_행은_남지_않는다() throws Exception {
        wish(WISHER, yesterday.atTime(10, 0));
        wish(OTHER_WISHER, yesterday.atTime(10, 0));
        job.run();

        rerun();
        assertUsageStats(WISHER).andExpect(jsonPath("$.data.wishCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM daily_member_usage WHERE business_date = ? AND member_id = ?",
                Date.valueOf(yesterday), WISHER)).isEqualTo(1);

        jdbc.update("DELETE FROM event_log WHERE member_id = ? AND campaign_id = ?", OTHER_WISHER, CAMPAIGN);
        rerun();

        assertThat(count("SELECT COUNT(*) FROM daily_member_usage WHERE member_id = ?", OTHER_WISHER)).isZero();
        assertUsageStats(WISHER).andExpect(jsonPath("$.data.wishCount").value(1));
    }

    @Test
    void 열세_달보다_오래된_행도_합산에_포함된다() throws Exception {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO daily_member_usage
                    (business_date, member_id, wish_count, redeem_count, saved_amount, created_at, updated_at)
                VALUES (?, ?, 3, 2, 4000, ?, ?), (?, ?, 1, 1, 500, ?, ?)
                """, Date.valueOf(today.minusMonths(30)), OLD_ROWS, now, now,
                Date.valueOf(today.minusMonths(2)), OLD_ROWS, now, now);

        assertUsageStats(OLD_ROWS)
                .andExpect(jsonPath("$.data.wishCount").value(4))
                .andExpect(jsonPath("$.data.redeemCount").value(3))
                .andExpect(jsonPath("$.data.savedAmount").value(4500));
    }

    private void rerun() {
        jdbc.update("DELETE FROM batch_execution_log");
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        job.run();
    }

    private ResultActions assertUsageStats(long memberId) throws Exception {
        return mvc.perform(get("/v1/members/me/usage-stats")
                        .with(user(new CustomUserDetails(memberId, Role.MEMBER))))
                .andExpect(status().isOk());
    }

    private long wishCountOf(long memberId, LocalDate date) {
        return count("SELECT COALESCE(SUM(wish_count), 0) FROM daily_member_usage "
                + "WHERE member_id = ? AND business_date = ?", memberId, Date.valueOf(date));
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private void wish(long memberId, LocalDateTime occurredAt) {
        event("wish", memberId, occurredAt);
    }

    private void event(String type, long memberId, LocalDateTime occurredAt) {
        Timestamp at = Timestamp.valueOf(occurredAt);
        Long wishId = type.equals("wish") ? 1L : null;
        jdbc.update("""
                INSERT INTO event_log (event_id, event_type, member_id, campaign_id, store_id, wish_id,
                                       occurred_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), type, memberId, CAMPAIGN, STORE, wishId, at, at, at);
    }

    private void sourceDone(LocalDate date) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, created_at, updated_at)
                VALUES ('BILLING_DAILY_RELEASE', ?, 'SUCCESS', ?, NULL, 'other-server', ?, ?)
                """, Date.valueOf(date), now, now, now);
    }
}
