package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.analytics.batch.job.DailyAggregationJob;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.global.config.ClockConfig;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 일별 집계의 두 입구를 실제 DB 로 확인한다. 시각(배치) 입구는 01:00 작업의 run(), HTTP 입구는
 * A-05 와 A-06 이다. 지금 등록된 단계는 정산 지표(1행), 회원 지표(1행), 가게 지표(1행), 정산 검증(0행)이라 공개 트랜잭션이 쓰는 행은 3 이다.
 */
class DailyAggregationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final String JOB = "DAILY_AGGREGATION";
    private static final String SOURCE_JOB = "BILLING_DAILY_RELEASE";

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
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM point_ledger");
    }

    @Test
    void 정산_대사가_끝난_날짜를_집계하고_성공으로_닫는다() {
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(statusOf(twoDaysAgo)).isEqualTo("SUCCESS");
        assertThat(processedCountOf(yesterday)).isEqualTo(3);
        assertThat(pendingDates()).isEmpty();
    }

    @Test
    void 정산_대사가_끝나지_않은_날짜는_건너뛰고_미처리로_남겼다가_다음_실행에서_집계한다() {
        sourceDone(yesterday);

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(statusOf(twoDaysAgo)).isNull();
        assertThat(pendingDates()).containsExactly(twoDaysAgo);

        sourceDone(twoDaysAgo);
        job.run();

        assertThat(statusOf(twoDaysAgo)).isEqualTo("SUCCESS");
        assertThat(pendingDates()).isEmpty();
    }

    @Test
    void 이미_성공한_날짜는_다음_실행에서_다시_집계한다() {
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        job.run();
        Timestamp firstFinish = finishedAtOf(yesterday);

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(finishedAtOf(yesterday)).isAfterOrEqualTo(firstFinish);
    }

    @Test
    void 다른_서버가_실행_중인_날짜는_건너뛴다() {
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        running(yesterday);

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("RUNNING");
        assertThat(statusOf(twoDaysAgo)).isEqualTo("SUCCESS");
    }

    @Test
    void 재계산을_접수하면_202이고_집계가_끝난_뒤_실행_조회로_확인한다() throws Exception {
        mvc.perform(post("/v1/admin/aggregations:recalculate")
                        .with(user(new CustomUserDetails(1L, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + yesterday + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.businessDate").value(yesterday.toString()))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        awaitStatus(yesterday, "SUCCESS");
        mvc.perform(get("/v1/admin/aggregation-runs")
                        .param("businessDate", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value(JOB))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.processedCount").value(3));
    }

    @Test
    void 오늘_날짜의_재계산은_422다() throws Exception {
        mvc.perform(post("/v1/admin/aggregations:recalculate")
                        .with(user(new CustomUserDetails(1L, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + today + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANALYTICS-002"));
    }

    @Test
    void 실행_중인_날짜의_재계산은_409다() throws Exception {
        running(yesterday);

        mvc.perform(post("/v1/admin/aggregations:recalculate")
                        .with(user(new CustomUserDetails(1L, Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + yesterday + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYTICS-003"));
    }

    @Test
    void 실행_기록이_없는_날짜의_조회는_data가_null이다() throws Exception {
        mvc.perform(get("/v1/admin/aggregation-runs")
                        .param("businessDate", yesterday.toString())
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void sourceDone(LocalDate date) {
        insertLog(SOURCE_JOB, date, "SUCCESS");
    }

    private void running(LocalDate date) {
        insertLog(JOB, date, "RUNNING");
    }

    private void insertLog(String jobName, LocalDate date, String status) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ClockConfig.ZONE));
        boolean finished = !"RUNNING".equals(status);
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, 'other-server', ?, ?)
                """, jobName, java.sql.Date.valueOf(date), status, finished ? now : null, now, now);
    }

    private String statusOf(LocalDate date) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                String.class, JOB, java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer processedCountOf(LocalDate date) {
        return jdbc.queryForObject(
                "SELECT processed_count FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                Integer.class, JOB, java.sql.Date.valueOf(date));
    }

    private Timestamp finishedAtOf(LocalDate date) {
        return jdbc.queryForObject(
                "SELECT finished_at FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                Timestamp.class, JOB, java.sql.Date.valueOf(date));
    }

    private List<LocalDate> pendingDates() {
        return jdbc.queryForList(
                "SELECT business_date FROM aggregation_pending_date ORDER BY business_date", LocalDate.class);
    }

    private void awaitStatus(LocalDate date, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(date))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(statusOf(date)).isEqualTo(expected);
    }
}
