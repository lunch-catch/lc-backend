package com.launchcatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.analytics.batch.job.EventLogRetentionJob;
import com.launchcatch.analytics.batch.service.EventLogRetentionService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.ops.service.BatchExecutionService;
import com.launchcatch.ops.service.DailyJobRunner;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 이벤트 로그 보존 삭제를 실제 MySQL 로 확인한다. 삭제 선(오늘 - 13개월 00:00)을 사이에 두고 오래된 행과
 * 남아야 하는 행을 심고, 1000행을 넘는 대상이 끝까지 지워지는지와 집계 표가 남는지를 본다.
 */
class EventLogRetentionIntegrationTest extends AbstractAnalyticsIntegrationTest {

    private static final long MEMBER = 981_000L;

    private final LocalDate today = LocalDate.now(ClockConfig.ZONE);
    private final LocalDateTime cutoff = today.minusMonths(13).atStartOfDay();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventLogRetentionJob job;
    @Autowired
    private BatchExecutionService executions;
    @Autowired
    private DailyJobRunner runner;
    @Autowired
    private EventLogRetentionService retention;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM event_log WHERE member_id = ?", MEMBER);
        jdbc.update("DELETE FROM daily_member_usage WHERE member_id = ?", MEMBER);
        jdbc.update("DELETE FROM batch_execution_log WHERE job_name = ?", EventLogRetentionJob.JOB_NAME);
    }

    @Test
    void 삭제_선보다_오래된_이벤트만_1000행_넘게도_끝까지_지운다() {
        insertEvents(2_500, cutoff.minusSeconds(1));
        insertEvents(1, cutoff);
        insertEvents(1, today.atStartOfDay());
        jdbc.update("""
                INSERT INTO daily_member_usage
                    (business_date, member_id, wish_count, redeem_count, saved_amount, created_at, updated_at)
                VALUES (?, ?, 1, 1, 1000, NOW(6), NOW(6))
                """, cutoff.toLocalDate().minusMonths(2), MEMBER);

        job.purgeExpiredEvents();

        assertThat(eventCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_log WHERE member_id = ? AND occurred_at = ?",
                Long.class, MEMBER, cutoff)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_member_usage WHERE member_id = ?",
                Long.class, MEMBER)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT processed_count FROM batch_execution_log WHERE job_name = ?",
                Integer.class, EventLogRetentionJob.JOB_NAME)).isEqualTo(2_500);
    }

    @Test
    void 같은_날_이미_끝났으면_다시_점유하지_않아_다시_지우지_않는다() {
        insertEvents(3, cutoff.minusDays(1));
        job.purgeExpiredEvents();
        insertEvents(3, cutoff.minusDays(1));

        job.purgeExpiredEvents();

        assertThat(eventCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_execution_log WHERE job_name = ?",
                Long.class, EventLogRetentionJob.JOB_NAME)).isEqualTo(1);
    }

    /*
     * 멈춘 실행을 이어받거나 관리자가 다시 돌리는 길은 DailyJobRunner.resume 이 부른다. runner 가 runCounting 이
     * 돌려준 건수로 닫아 지운 건수가 남는지 본다.
     */
    @Test
    void 이어받아_다시_돌려도_삭제_건수가_실행_기록에_남는다() {
        insertEvents(5, cutoff.minusDays(1));
        LocalDate businessDate = executions.businessDate();
        executions.claim(EventLogRetentionJob.JOB_NAME, businessDate);

        runner.resume(EventLogRetentionJob.JOB_NAME, businessDate, 1);

        assertThat(eventCount()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT processed_count FROM batch_execution_log WHERE job_name = ?",
                Integer.class, EventLogRetentionJob.JOB_NAME)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM batch_execution_log WHERE job_name = ?",
                String.class, EventLogRetentionJob.JOB_NAME)).isEqualTo("SUCCESS");
    }

    /*
     * 운영 이미지는 JVM 시간대를 따로 주지 않아 UTC 로 뜰 수 있다. 그때도 삭제 선이 KST 자정에서 밀리지 않는지 본다.
     * 삭제 선을 Timestamp 로 넘기면 드라이버가 9시간을 옮겨 아래 두 남을 행까지 지운다.
     */
    @Test
    void 서버_기본_시간대가_UTC여도_KST_자정을_삭제_선으로_쓴다() {
        insertEvents(1, cutoff.minusSeconds(1));
        insertEvents(1, cutoff);
        insertEvents(1, cutoff.plusHours(8));
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            assertThat(retention.purge()).isEqualTo(1);
        } finally {
            TimeZone.setDefault(original);
        }

        assertThat(eventCount()).isEqualTo(2);
    }

    private Long eventCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_log WHERE member_id = ?", Long.class, MEMBER);
    }

    // 화면 조회 이벤트는 대상 캠페인과 가게가 없어 키 제약 없이 심을 수 있다.
    private void insertEvents(int count, LocalDateTime occurredAt) {
        jdbc.batchUpdate("""
                INSERT INTO event_log
                    (event_id, event_type, member_id, occurred_at, created_at, updated_at)
                VALUES (UUID(), 'map_view', ?, ?, ?, ?)
                """, IntStream.range(0, count).mapToObj(index ->
                new Object[] {MEMBER, occurredAt, occurredAt, occurredAt}).toList());
    }
}
