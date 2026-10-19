package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.FraudMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 캠페인 부정 경고 원천(daily_campaign_fraud_analytics)을 쓴다.
 *
 * 다시 집계했을 때 노출이 사라진 캠페인의 옛 행이 남지 않게 그날 행을 모두 지우고 넣는다. 부른 쪽이 연 트랜잭션
 * 안에서 돈다.
 */
@Repository
public class FraudAnalyticsRepository {

    private static final String DELETE = "DELETE FROM daily_campaign_fraud_analytics WHERE business_date = ?";

    private static final String INSERT = """
            INSERT INTO daily_campaign_fraud_analytics (
                business_date, campaign_id, valid_count, invalid_count, distinct_member_count,
                top_members_count, concentration_suspected, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public FraudAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, List<FraudMetrics.Row> rows, LocalDateTime now) {
        Date date = Date.valueOf(businessDate);
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(DELETE, date);
        jdbc.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setDate(1, date);
            statement.setLong(2, row.campaignId());
            statement.setLong(3, row.validCount());
            statement.setLong(4, row.invalidCount());
            statement.setLong(5, row.distinctMemberCount());
            statement.setLong(6, row.topMembersCount());
            statement.setBoolean(7, row.concentrationSuspected());
            statement.setTimestamp(8, at);
            statement.setTimestamp(9, at);
        });
    }
}
