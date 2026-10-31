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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 퍼널의 찜 수가 event_log 의 wish 행에서 영업일 경계대로 세어지는지를 실제 MySQL 로 끝까지 확인한다.
 * 찜 수는 돈과 맞아야 하는 값은 아니지만 31일 보관인 swipe_log 대신 event_log 를 원천으로 바꾼 결정이
 * 걸려 있고, 직접 쓴 집계 SQL 과 V3 표를 한 번은 실제로 돌려 봐야 해서 둔다. 입구는 시각(배치)과
 * HTTP(GET /v1/admin/dashboard/funnel) 둘이다.
 *
 * 노출과 쿠폰은 다른 테스트가 남긴 행이 같은 DB 에 있을 수 있어 건수를 단정하지 않는다. 찜은 심기 전
 * 값에서 늘어난 만큼을 본다.
 */
class FunnelAggregationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long CAMPAIGN = 972_100L;
    private static final long STORE = 972_200L;
    private static final long MEMBER = 972_300L;

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
        jdbc.update("DELETE FROM daily_funnel_analytics");
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM event_log WHERE campaign_id = ?", CAMPAIGN);
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
    }

    @Test
    void 찜_수는_이벤트_로그의_wish_행을_영업일_경계대로_세어_저장하고_조회된다() throws Exception {
        long baseWish = count("SELECT COUNT(*) FROM event_log WHERE event_type = 'wish' "
                + "AND occurred_at >= ? AND occurred_at < ?", start(yesterday), start(today));

        event("wish", yesterday.atStartOfDay());
        event("wish", yesterday.atTime(23, 59, 59, 999_999_000));
        event("wish", today.atStartOfDay());
        event("wish", yesterday.atStartOfDay().minusNanos(1_000));
        event("pass", yesterday.atTime(12, 0));
        event("impression", yesterday.atTime(12, 0));

        job.run();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM daily_funnel_analytics WHERE business_date = ?", Date.valueOf(yesterday));
        assertThat(((Number) row.get("wish_count")).longValue()).isEqualTo(baseWish + 2);

        mvc.perform(get("/v1/admin/dashboard/funnel")
                        .param("period", "DAILY")
                        .param("from", yesterday.toString())
                        .param("to", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FINAL"))
                .andExpect(jsonPath("$.data.items[0].wishCount").value(baseWish + 2))
                .andExpect(jsonPath("$.data.items[0].redeemCount").exists());
    }

    @Test
    void 같은_날짜를_다시_집계해도_찜_수는_같은_값이다() {
        event("wish", yesterday.atTime(10, 30));
        job.run();
        long first = wishCountOf(yesterday);

        jdbc.update("DELETE FROM batch_execution_log");
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        job.run();

        assertThat(wishCountOf(yesterday)).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM daily_funnel_analytics WHERE business_date = ?",
                Date.valueOf(yesterday))).isEqualTo(1);
    }

    private long wishCountOf(LocalDate date) {
        return count("SELECT wish_count FROM daily_funnel_analytics WHERE business_date = ?", Date.valueOf(date));
    }

    private Timestamp start(LocalDate date) {
        return Timestamp.valueOf(date.atStartOfDay());
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private void event(String type, LocalDateTime occurredAt) {
        Timestamp at = Timestamp.valueOf(occurredAt);
        Long wishId = type.equals("wish") ? 1L : null;
        jdbc.update("""
                INSERT INTO event_log (event_id, event_type, member_id, campaign_id, store_id, wish_id,
                                       occurred_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), type, MEMBER, CAMPAIGN, STORE, wishId, at, at, at);
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
