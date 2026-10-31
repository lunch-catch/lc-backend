package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.billing.service.DailyReleaseMarker;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 해제 완료 표시(BILLING_DAILY_RELEASE)의 복구 경로를 실제 MySQL 8.4 와 실제 배치 실행 서비스로 검증한다(7-4).
 * 분석 01:00 집계가 이 행의 SUCCESS 로 원천 확정을 판정하므로, 실패로 남은 표시는 다시 닫혀야 하고 다른 서버가 지금
 * 실행 중인 행은 성공으로 닫히면 안 된다. 다른 테스트와 섞이지 않게 먼 미래 영업일을 쓴다.
 */
class DailyReleaseMarkerIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2031, 6, 1);
    private static final String JOB = "BILLING_DAILY_RELEASE";

    @Autowired DailyReleaseMarker marker;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from batch_execution_log where job_name = ? and business_date = ?", JOB, DAY);
    }

    private String status() {
        return jdbc.queryForObject(
                "select status from batch_execution_log where job_name = ? and business_date = ?",
                String.class, JOB, DAY);
    }

    @Test
    void 표시가_없으면_성공으로_남기고_다시_불러도_그대로다() {
        marker.mark(DAY);
        marker.mark(DAY);

        assertThat(status()).isEqualTo("SUCCESS");
    }

    @Test
    void 실패로_남은_표시는_다시_열어_성공으로_닫는다() {
        jdbc.update("""
                insert into batch_execution_log
                    (job_name, business_date, status, finished_at, failure_reason, owner_id, created_at, updated_at)
                values (?, ?, 'FAILED', now(6), '표시 기록 중단', 'other-server', now(6), now(6))
                """, JOB, DAY);

        marker.mark(DAY);

        assertThat(status()).isEqualTo("SUCCESS");
    }

    @Test
    void 다른_서버가_지금_실행_중인_행은_성공으로_닫지_않는다() {
        jdbc.update("""
                insert into batch_execution_log
                    (job_name, business_date, status, owner_id, created_at, updated_at)
                values (?, ?, 'RUNNING', 'other-server', now(6), now(6))
                """, JOB, DAY);

        marker.mark(DAY);

        assertThat(status()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "select owner_id from batch_execution_log where job_name = ? and business_date = ?",
                String.class, JOB, DAY)).isEqualTo("other-server");
    }
}
