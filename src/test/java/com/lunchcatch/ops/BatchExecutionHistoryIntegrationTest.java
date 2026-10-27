package com.lunchcatch.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.ops.scheduler.BatchJobRegistry;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/*
 * GET /v1/admin/batch-executions 를 실제 입구부터 DB 조회까지 확인한다(#575).
 *
 * 다른 ops 통합 테스트와 행이 섞이지 않게 먼 미래의 영업일을 쓰고 끝나면 그 범위만 지운다.
 */
class BatchExecutionHistoryIntegrationTest extends AbstractOpsIntegrationTest {

    private static final LocalDate DAY1 = LocalDate.of(2032, 5, 1);
    private static final LocalDate DAY2 = LocalDate.of(2032, 5, 2);
    private static final Long ADMIN_ID = 9003L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM batch_execution_log WHERE business_date BETWEEN ? AND ?",
                java.sql.Date.valueOf(DAY1), java.sql.Date.valueOf(DAY2));
    }

    @Test
    void 기간_안의_기록을_영업일_내림차순으로_돌려주고_시각에_오프셋을_붙인다() throws Exception {
        insert("EVENT_LOG_RETENTION", DAY1, "SUCCESS", 7, 1);
        insert("LOAD_DAILY_CANDIDATE", DAY1, "RUNNING", null, 1);
        insert(BatchJobRegistry.BUNDLE, DAY2, "FAILED", null, 1);

        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].jobName").value(BatchJobRegistry.BUNDLE))
                .andExpect(jsonPath("$.data.items[0].businessDate").value(DAY2.toString()))
                .andExpect(jsonPath("$.data.items[0].startedAt", endsWith("+09:00")))
                .andExpect(jsonPath("$.data.items[0].finishedAt", endsWith("+09:00")))
                .andExpect(jsonPath("$.data.items[0].failureReason").value("테스트 실패"))
                .andExpect(jsonPath("$.data.items[1].jobName").value("LOAD_DAILY_CANDIDATE"))
                .andExpect(jsonPath("$.data.items[1].finishedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.items[2].jobName").value("EVENT_LOG_RETENTION"))
                .andExpect(jsonPath("$.data.items[2].processedCount").value(7))
                .andExpect(jsonPath("$.data.nextPageToken").value(nullValue()));
    }

    @Test
    void 작업명과_상태로_거른다() throws Exception {
        insert(BatchJobRegistry.BUNDLE, DAY2, "FAILED", null, 1);
        insert("RESERVE_DAILY_BUDGET", DAY2, "FAILED", null, 1);
        insert("EVENT_LOG_RETENTION", DAY2, "SUCCESS", 3, 1);

        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY2.toString()).param("to", DAY2.toString()).param("status", "FAILED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY2.toString()).param("to", DAY2.toString())
                        .param("jobName", "EVENT_LOG_RETENTION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].jobName").value("EVENT_LOG_RETENTION"));
    }

    /*
     * 00:00 묶음은 재실행할 수 있고 그 개별 단계는 재실행할 수 없다. 화면은 retryable 로 버튼을 가린다.
     */
    @Test
    void 실패한_묶음만_retryable이고_실패한_단계는_아니다() throws Exception {
        insert(BatchJobRegistry.BUNDLE, DAY2, "FAILED", null, 1);
        insert("RESERVE_DAILY_BUDGET", DAY2, "FAILED", null, 1);

        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY2.toString()).param("to", DAY2.toString())
                        .param("jobName", BatchJobRegistry.BUNDLE)))
                .andExpect(jsonPath("$.data.items[0].retryable").value(true));
        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY2.toString()).param("to", DAY2.toString())
                        .param("jobName", "RESERVE_DAILY_BUDGET")))
                .andExpect(jsonPath("$.data.items[0].retryable").value(false));
    }

    // 실행 기록은 (작업명, 영업일)마다 한 행이라 재실행한 기록도 한 항목으로 나오고 오른 세대를 담는다
    @Test
    void 재실행한_기록은_한_항목으로_나오고_오른_세대를_담는다() throws Exception {
        insert(BatchJobRegistry.BUNDLE, DAY2, "RUNNING", null, 2);

        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY2.toString()).param("to", DAY2.toString())))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].attemptNo").value(2));
    }

    @Test
    void pageToken으로_넘기면_항목이_겹치거나_빠지지_않는다() throws Exception {
        insert("JOB_A", DAY1, "SUCCESS", null, 1);
        insert("JOB_B", DAY2, "SUCCESS", null, 1);
        insert("JOB_C", DAY2, "SUCCESS", null, 1);

        String first = mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("pageSize", "2")))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(first, "$.data.nextPageToken");
        String second = mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("pageSize", "2")
                        .param("pageToken", token)))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.nextPageToken").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        List<String> names = new ArrayList<>(JsonPath.read(first, "$.data.items[*].jobName"));
        names.addAll(JsonPath.read(second, "$.data.items[*].jobName"));
        assertThat(names).containsExactlyInAnyOrder("JOB_A", "JOB_B", "JOB_C");
    }

    @Test
    void 기간이_없거나_31일을_넘으면_OPS_004다() throws Exception {
        mvc.perform(admin(get("/v1/admin/batch-executions").param("from", DAY1.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPS-004"));
        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", "2032-05-01").param("to", "2032-06-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPS-004"));
    }

    @Test
    void 상태_값이_아니면_COMMON_002다() throws Exception {
        mvc.perform(admin(get("/v1/admin/batch-executions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString()).param("status", "DONE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        mvc.perform(get("/v1/admin/batch-executions")
                        .param("from", DAY1.toString()).param("to", DAY2.toString())
                        .with(user(new CustomUserDetails(1L, Role.OWNER))))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.with(user(new CustomUserDetails(ADMIN_ID, Role.ADMIN)));
    }

    private void insert(String jobName, LocalDate date, String status, Integer processedCount, int attemptNo) {
        LocalDateTime started = date.atStartOfDay();
        boolean finished = !"RUNNING".equals(status);
        jdbc.update("""
                INSERT INTO batch_execution_log
                    (job_name, business_date, status, finished_at, failure_reason, processed_count,
                     owner_id, retry_count, attempt_no, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'batch-02', 0, ?, ?, ?)
                """, jobName, java.sql.Date.valueOf(date), status,
                finished ? Timestamp.valueOf(started.plusMinutes(3)) : null,
                "FAILED".equals(status) ? "테스트 실패" : null, processedCount, attemptNo,
                Timestamp.valueOf(started), Timestamp.valueOf(started));
    }
}
