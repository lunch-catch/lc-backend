package com.lunchcatch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 관리자 수동 재실행으로 묶음 밖 작업을 다시 돌리면 runCounting 이 돌려준 건수가
 * batch_execution_log.processed_count 에 남는지 실제 입구부터 DB 재조회까지 확인한다(#559).
 * 이어받기도 같은 BatchJobRunner.resume 을 탄다.
 */
class StandaloneJobRunCountingIntegrationTest extends AbstractOpsIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2031, 4, 8);
    private static final Long ADMIN_ID = 9002L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log WHERE job_name IN (?, ?) AND business_date = ?",
                TestStandaloneJobs.COUNTED, TestStandaloneJobs.UNCOUNTED, java.sql.Date.valueOf(DATE));
        jdbc.update("DELETE FROM audit_log WHERE admin_id = ?", ADMIN_ID);
    }

    @Test
    void runCounting을_재정의한_작업을_재실행하면_돌려준_건수가_processed_count에_남는다() throws Exception {
        insertFailed(TestStandaloneJobs.COUNTED);

        retry(TestStandaloneJobs.COUNTED);

        Map<String, Object> row = awaitSuccess(TestStandaloneJobs.COUNTED);
        assertThat(((Number) row.get("processed_count")).intValue()).isEqualTo(TestStandaloneJobs.COUNT);
        assertThat(((Number) row.get("attempt_no")).intValue()).isEqualTo(2);
    }

    @Test
    void runCounting을_재정의하지_않은_작업을_재실행하면_processed_count가_null이다() throws Exception {
        insertFailed(TestStandaloneJobs.UNCOUNTED);

        retry(TestStandaloneJobs.UNCOUNTED);

        Map<String, Object> row = awaitSuccess(TestStandaloneJobs.UNCOUNTED);
        assertThat(row.get("processed_count")).isNull();
    }

    private void retry(String jobName) throws Exception {
        mvc.perform(post("/v1/admin/batch-executions/" + jobName + "/" + DATE + ":retry")
                        .with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN))))
                .andExpect(status().isAccepted());
    }

    private void insertFailed(String jobName) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, failure_reason, processed_count,
                     owner_id, retry_count, attempt_no, created_at, updated_at)
                VALUES (?, ?, 'FAILED', ?, '테스트 실패', NULL, 'other-server', 0, 1, ?, ?)
                """, jobName, java.sql.Date.valueOf(DATE), now, now, now);
    }

    private Map<String, Object> awaitSuccess(String jobName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, processed_count, attempt_no FROM batch_execution_log"
                            + " WHERE job_name = ? AND business_date = ?",
                    jobName, java.sql.Date.valueOf(DATE));
            if (!rows.isEmpty() && "SUCCESS".equals(rows.get(0).get("status"))) {
                return rows.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError(jobName + " 이 10초 안에 SUCCESS 로 닫히지 않았다");
    }
}
