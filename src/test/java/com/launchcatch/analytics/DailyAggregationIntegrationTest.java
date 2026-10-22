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
import com.launchcatch.ops.job.DailyJobScheduler;
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
 * 일별 집계의 세 입구를 실제 DB 로 확인한다. 시각(배치) 입구는 01:00 작업의 run(), HTTP 입구는
 * A-05 와 A-06 이다. 다른 도메인의 호출 입구는 ops 의 이어받기와 관리자 수동 재실행이 부르는
 * StandaloneJob.run 이다. 지금 등록된 단계는 정산 지표(1행), 회원 지표(1행), 가게 지표(1행), 퍼널 지표(1행), 정산 검증(0행)이라 공개 트랜잭션이 쓰는 행은 4 이다.
 * 캠페인 리포트(#625)는 캠페인마다 1행이다. 같은 DB 에 다른 테스트가 남긴 캠페인과 이벤트에 따라 행 수가 달라진다.
 * 그래서 리포트 표를 비우고 시작해 그날 저장된 리포트 행 수를 4 에 더해 비교한다.
 */
class DailyAggregationIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final String JOB = "DAILY_AGGREGATION";
    private static final String SOURCE_JOB = "BILLING_DAILY_RELEASE";
    private static final String OTHER = "other-server";

    private final LocalDate today = LocalDate.now(ClockConfig.ZONE);
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate twoDaysAgo = today.minusDays(2);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private DailyAggregationJob job;
    @Autowired
    private DailyJobScheduler scheduler;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log");
        jdbc.update("DELETE FROM aggregation_pending_date");
        jdbc.update("DELETE FROM daily_settlement_analytics");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM campaign_daily_impression_reason_report");
        jdbc.update("DELETE FROM campaign_hourly_report");
        jdbc.update("DELETE FROM campaign_daily_slot_report");
        jdbc.update("DELETE FROM campaign_daily_report_history");
        jdbc.update("DELETE FROM campaign_daily_report");
    }

    @Test
    void 정산_대사가_끝난_날짜를_집계하고_성공으로_닫는다() {
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(statusOf(twoDaysAgo)).isEqualTo("SUCCESS");
        assertThat(processedCountOf(yesterday)).isEqualTo(publishedRows(yesterday));
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

    /*
     * 여러 날 집계가 멈췄고 미처리 기록도 남지 않은 날짜가 전전일 밖에 있다(7-9). 원천이 확정됐는데 집계가 SUCCESS 가 아닌
     * 날짜를 실행 기록에서 찾아 집계하고, 이미 집계가 끝난 날짜는 다시 돌리지 않는다.
     */
    @Test
    void 전전일_밖에서_원천이_확정됐는데_집계되지_않은_날짜를_찾아_집계한다() {
        LocalDate missed = today.minusDays(6);
        LocalDate aggregated = today.minusDays(5);
        sourceDone(missed);
        sourceDone(aggregated);
        insertLog(JOB, aggregated, "SUCCESS");
        Timestamp aggregatedFinish = finishedAtOf(aggregated);
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);

        job.run();

        assertThat(statusOf(missed)).isEqualTo("SUCCESS");
        assertThat(finishedAtOf(aggregated)).isEqualTo(aggregatedFinish);
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
    void 실패한_날짜는_retry로_다시_잡아_오른_세대로_공개한다() {
        sourceDone(yesterday);
        sourceDone(twoDaysAgo);
        insertLog(JOB, yesterday, "FAILED", OTHER, 1, now());

        job.run();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(attemptNoOf(yesterday)).isEqualTo(2);
        assertThat(processedCountOf(yesterday)).isEqualTo(publishedRows(yesterday));
    }

    /*
     * 다른 도메인의 호출 입구다. ops 의 이어받기 스캔이 멈춘 행을 가져와 StandaloneJob 으로 이 작업을 다시 돌린다.
     * 전전일도 집계할 수 있게 두어, 이어받은 날짜만 돌고 01:00 전체 실행을 다시 하지 않는지 함께 본다.
     */
    @Test
    void 멈춘_집계를_이어받은_서버가_넘겨받은_세대로_그_날짜만_집계하고_미처리를_지운다() {
        sourceDone(twoDaysAgo);
        insertLog(JOB, yesterday, "RUNNING", OTHER, 1, now().minusMinutes(5));
        pendingDate(yesterday);

        scheduler.takeOverStale();

        assertThat(statusOf(yesterday)).isEqualTo("SUCCESS");
        assertThat(ownerOf(yesterday)).isEqualTo(BATCH_OWNER);
        assertThat(attemptNoOf(yesterday)).isEqualTo(2);
        assertThat(processedCountOf(yesterday)).isEqualTo(publishedRows(yesterday));
        assertThat(pendingDates()).isEmpty();
        assertThat(statusOf(twoDaysAgo)).isNull();
    }

    @Test
    void 관리자_수동_재실행이_실패한_집계_날짜를_다시_집계한다() throws Exception {
        insertLog(JOB, yesterday, "FAILED", OTHER, 1, now());

        mvc.perform(post("/v1/admin/batch-executions/" + JOB + "/" + yesterday + ":retry")
                        .with(user(new CustomUserDetails(1L, Role.ADMIN))))
                .andExpect(status().isAccepted());

        awaitStatus(yesterday, "SUCCESS");
        assertThat(attemptNoOf(yesterday)).isEqualTo(2);
        assertThat(processedCountOf(yesterday)).isEqualTo(publishedRows(yesterday));
    }

    /*
     * 같은 서버가 새 세대(2)를 잡은 뒤 세대 1 의 오래된 실행이 늦게 공개하려는 경우다. 소유자가 같아 생존 신호와
     * 소유자 조건으로는 가려지지 않고 공개 트랜잭션의 세대 확인만 막는다.
     */
    @Test
    void 같은_서버라도_세대가_바뀌면_이전_실행은_공개하지_못하고_새_시도의_상태와_미처리를_건드리지_않는다() {
        insertLog(JOB, yesterday, "RUNNING", BATCH_OWNER, 2, now());
        pendingDate(yesterday);

        job.run(yesterday, 1);

        assertThat(statusOf(yesterday)).isEqualTo("RUNNING");
        assertThat(attemptNoOf(yesterday)).isEqualTo(2);
        assertThat(processedCountOf(yesterday)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT retry_count FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                Integer.class, JOB, java.sql.Date.valueOf(yesterday))).isZero();
        assertThat(pendingDates()).containsExactly(yesterday);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_settlement_analytics WHERE business_date = ?",
                Long.class, java.sql.Date.valueOf(yesterday))).isZero();
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
                .andExpect(jsonPath("$.data.processedCount").value(publishedRows(yesterday)));
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
        insertLog(jobName, date, status, OTHER, 1, now());
    }

    private void insertLog(String jobName, LocalDate date, String status, String owner, int attemptNo,
                           LocalDateTime updatedAt) {
        Timestamp at = Timestamp.valueOf(updatedAt);
        boolean finished = !"RUNNING".equals(status);
        String reason = "FAILED".equals(status) ? "앞 시도가 실패했다" : null;
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, processed_count, owner_id, failure_reason,
                     attempt_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                """, jobName, java.sql.Date.valueOf(date), status, finished ? at : null, owner, reason,
                attemptNo, at, at);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ClockConfig.ZONE);
    }

    private void pendingDate(LocalDate date) {
        Timestamp at = Timestamp.valueOf(now());
        jdbc.update("""
                INSERT INTO aggregation_pending_date (business_date, reason, recorded_at, created_at, updated_at)
                VALUES (?, 'FAILED', ?, ?, ?)
                """, java.sql.Date.valueOf(date), at, at, at);
    }

    private Integer attemptNoOf(LocalDate date) {
        return jdbc.queryForObject(
                "SELECT attempt_no FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                Integer.class, JOB, java.sql.Date.valueOf(date));
    }

    private String ownerOf(LocalDate date) {
        return jdbc.queryForObject(
                "SELECT owner_id FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                String.class, JOB, java.sql.Date.valueOf(date));
    }

    private String statusOf(LocalDate date) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                String.class, JOB, java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int publishedRows(LocalDate date) {
        Integer reports = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_daily_report WHERE business_date = ?",
                Integer.class, java.sql.Date.valueOf(date));
        return 4 + (reports == null ? 0 : reports);
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
