package com.lunchcatch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.ops.job.DailyJobScheduler;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 관리자 수동 재실행으로 묶음 밖 작업을 다시 돌리면 runCounting 이 돌려준 건수가
 * batch_execution_log.processed_count 에 남는지 실제 입구부터 DB 재조회까지 확인한다(#559).
 * 수동 재실행은 배치 서버의 이어받기가 BatchJobRunner.resume 으로 돈다(#1231). 이 컨텍스트는 스케줄러가 꺼져 있어
 * 테스트가 이어받기 스캔을 대신 부른다.
 */
class StandaloneJobRunCountingIntegrationTest extends AbstractOpsIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2031, 4, 8);
    private static final Long ADMIN_ID = 9002L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DailyJobScheduler scheduler;

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
        scheduler.takeOverStale();

        Map<String, Object> row = rowOf(TestStandaloneJobs.COUNTED);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("processed_count")).intValue()).isEqualTo(TestStandaloneJobs.COUNT);
        // 재실행 요청과 이어받기에서 세대가 하나씩 오른다
        assertThat(((Number) row.get("attempt_no")).intValue()).isEqualTo(3);
    }

    @Test
    void runCounting을_재정의하지_않은_작업을_재실행하면_processed_count가_null이다() throws Exception {
        insertFailed(TestStandaloneJobs.UNCOUNTED);

        retry(TestStandaloneJobs.UNCOUNTED);
        scheduler.takeOverStale();

        Map<String, Object> row = rowOf(TestStandaloneJobs.UNCOUNTED);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
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

    private Map<String, Object> rowOf(String jobName) {
        return jdbc.queryForMap(
                "SELECT status, processed_count, attempt_no FROM batch_execution_log"
                        + " WHERE job_name = ? AND business_date = ?",
                jobName, java.sql.Date.valueOf(DATE));
    }
}
