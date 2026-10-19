package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.NotBilledMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 미과금 노출 지표(daily_campaign_not_billed_analytics)를 쓴다.
 *
 * 다시 집계했을 때 사라진 사유의 옛 행이 남지 않게 그날 행을 모두 지우고 넣는다. 부른 쪽이 연 트랜잭션 안에서 돈다.
 */
@Repository
public class NotBilledAnalyticsRepository {

    private static final String DELETE = "DELETE FROM daily_campaign_not_billed_analytics WHERE business_date = ?";

    private static final String INSERT = """
            INSERT INTO daily_campaign_not_billed_analytics (
                business_date, campaign_id, not_billed_reason, not_billed_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public NotBilledAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, List<NotBilledMetrics.Row> rows, LocalDateTime now) {
        Date date = Date.valueOf(businessDate);
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(DELETE, date);
        jdbc.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setDate(1, date);
            statement.setLong(2, row.campaignId());
            statement.setString(3, row.reason().name());
            statement.setLong(4, row.count());
            statement.setTimestamp(5, at);
            statement.setTimestamp(6, at);
        });
    }
}
