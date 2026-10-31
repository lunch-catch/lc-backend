package com.lunchcatch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.ops.exception.OpsErrorCode;
import com.lunchcatch.ops.exception.OpsException;
import com.lunchcatch.ops.scheduler.BatchJobRegistry;
import com.lunchcatch.ops.service.BatchExecutionRetryService;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * POST /v1/admin/batch-executions/{jobName}/{businessDate}:retry 를 실제 입구부터
 * DB 재조회까지 확인한다(#362).
 *
 * 00:00 묶음(DAILY_0000)으로 확인한다. 지금 운영 컨텍스트에는 등록된 DailyJob 과 StandaloneJob
 * 이 없어(아직 비어 있는 것, 런치캐치_배치_운영.md), 등록된 StandaloneJob 이 받는 분기는
 * BatchJobRunner.resume 에 올바른 인자로 위임하는지를 단위 테스트(BatchExecutionRetryServiceTest)
 * 와 그 분기 자체의 동작(BatchJobRunnerTest)으로 나눠 확인했다.
 */
class BatchExecutionRetryIntegrationTest extends AbstractOpsIntegrationTest {

    private static final String JOB = BatchJobRegistry.BUNDLE;
    private static final LocalDate DATE = LocalDate.of(2031, 3, 17);
    private static final Long ADMIN_ID = 9001L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BatchExecutionRetryService service;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                JOB, java.sql.Date.valueOf(DATE));
        jdbc.update("DELETE FROM audit_log WHERE admin_id = ?", ADMIN_ID);
    }

    @Test
    void FAILED_행을_재실행하면_202를_주고_결과로_행을_SUCCESS로_닫고_감사_로그를_남긴다() throws Exception {
        insertFailed(JOB, DATE, 1);

        mvc.perform(post(path(JOB, DATE))
                        .with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobName").value(JOB))
                .andExpect(jsonPath("$.data.businessDate").value(DATE.toString()))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        awaitStatus(JOB, DATE, "SUCCESS");
        assertThat(attemptNoOf(JOB, DATE)).isEqualTo(2);
        List<Long> auditAdmins = jdbc.queryForList(
                "SELECT admin_id FROM audit_log WHERE admin_id = ? AND action = 'BATCH_EXECUTION_RETRY'",
                Long.class, ADMIN_ID);
        assertThat(auditAdmins).hasSize(1);
    }

    @Test
    void SUCCESS_행의_재실행은_409다() throws Exception {
        insertLog(JOB, DATE, "SUCCESS", 1);

        mvc.perform(post(path(JOB, DATE))
                        .with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS-003"));
    }

    @Test
    void 실행_기록이_없으면_404다() throws Exception {
        mvc.perform(post(path(JOB, DATE))
                        .with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPS-002"));
    }

    @Test
    void 묶음도_등록된_작업도_아닌_이름은_422다() throws Exception {
        String orphan = "BATCH_RETRY_TEST_ORPHAN_STEP";
        insertFailed(orphan, DATE, 1);

        mvc.perform(post(path(orphan, DATE))
                        .with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS-001"));

        jdbc.update("DELETE FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                orphan, java.sql.Date.valueOf(DATE));
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        insertFailed(JOB, DATE, 1);

        mvc.perform(post(path(JOB, DATE))
                        .with(user(new CustomUserDetails(1L, Role.OWNER))))
                .andExpect(status().isForbidden());
    }

    /*
     * 동시에 두 요청이 같은 FAILED 행을 재실행하면, BatchExecutionService.retry 의 조건부
     * UPDATE 가 한쪽만 걸려야 한다. 둘 다 걸리면 작업이 두 번 돈다.
     *
     * 서비스를 직접 두 스레드에서 부른다. CampaignBudgetPeriodIntegrationTest 와 같은 이유로,
     * 메서드를 직접 부르는 입구에는 테스트 트랜잭션을 걸지 않는다(AbstractOpsIntegrationTest 가
     * 클래스 단위 @Transactional 을 두지 않아 그대로 적용된다).
     */
    @Test
    void 동시에_두_번_오면_한_번만_걸린다() throws Exception {
        insertFailed(JOB, DATE, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = List.of(
                    executor.submit(() -> attemptRetry(ready, go)),
                    executor.submit(() -> attemptRetry(ready, go)));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            long succeeded = results.stream().map(this::get).filter(Boolean::booleanValue).count();
            long conflicted = results.stream().map(this::get).filter(accepted -> !accepted).count();
            assertThat(succeeded).isEqualTo(1);
            assertThat(conflicted).isEqualTo(1);
        }

        awaitStatus(JOB, DATE, "SUCCESS");
    }

    /** 성공해 접수됐으면 true, OPS-003(FAILED 아님)으로 거부됐으면 false다. 다른 예외는 그대로 던진다. */
    private boolean attemptRetry(CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        try {
            service.retry(ADMIN_ID, JOB, DATE);
            return true;
        } catch (OpsException e) {
            if (e.getErrorCode() == OpsErrorCode.EXECUTION_NOT_FAILED) {
                return false;
            }
            throw e;
        }
    }

    private Boolean get(Future<Boolean> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String path(String jobName, LocalDate businessDate) {
        return "/v1/admin/batch-executions/" + jobName + "/" + businessDate + ":retry";
    }

    private void insertFailed(String jobName, LocalDate date, int attemptNo) {
        insertLog(jobName, date, "FAILED", attemptNo);
    }

    private void insertLog(String jobName, LocalDate date, String status, int attemptNo) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        boolean finished = !"RUNNING".equals(status);
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, failure_reason, processed_count,
                     owner_id, retry_count, attempt_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NULL, 'other-server', 0, ?, ?, ?)
                """, jobName, java.sql.Date.valueOf(date), status,
                finished ? now : null, finished && "FAILED".equals(status) ? "테스트 실패" : null,
                attemptNo, now, now);
    }

    private String statusOf(String jobName, LocalDate date) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                String.class, jobName, java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer attemptNoOf(String jobName, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT attempt_no FROM batch_execution_log WHERE job_name = ? AND business_date = ?",
                Integer.class, jobName, java.sql.Date.valueOf(date));
    }

    private void awaitStatus(String jobName, LocalDate date, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(jobName, date))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(statusOf(jobName, date)).isEqualTo(expected);
    }
}
