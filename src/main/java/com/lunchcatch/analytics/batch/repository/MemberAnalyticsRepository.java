package com.lunchcatch.analytics.batch.repository;

import com.lunchcatch.analytics.batch.MemberMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 회원 지표(daily_member_analytics)를 쓴다.
 *
 * 그날 행은 한 문장으로 교체한다(INSERT ... ON DUPLICATE KEY UPDATE). created_at 은 처음 만든 시각으로
 * 두고 갱신하지 않는다.
 */
@Repository
public class MemberAnalyticsRepository {

    private static final String UPSERT = """
            INSERT INTO daily_member_analytics (
                business_date, new_member_count, cumulative_member_count, active_member_count,
                withdrawn_count, suspended_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                new_member_count = incoming.new_member_count,
                cumulative_member_count = incoming.cumulative_member_count,
                active_member_count = incoming.active_member_count,
                withdrawn_count = incoming.withdrawn_count,
                suspended_count = incoming.suspended_count,
                updated_at = incoming.updated_at
            """;

    private final JdbcTemplate jdbc;

    public MemberAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, MemberMetrics metrics, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(UPSERT, Date.valueOf(businessDate), metrics.newMemberCount(),
                metrics.cumulativeMemberCount(), metrics.activeMemberCount(), metrics.withdrawnCount(),
                metrics.suspendedCount(), at, at);
    }
}
