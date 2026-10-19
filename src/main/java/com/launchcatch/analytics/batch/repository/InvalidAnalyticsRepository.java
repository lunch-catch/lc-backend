package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.InvalidMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 무효 노출 지표(daily_invalid_analytics)를 쓴다.
 *
 * 이 표의 기본 키는 대리 키라 행이 여럿인 날은 upsert 로 교체할 수 없다. 그날 행을 모두 지우고 넣는다. 부른 쪽이
 * 연 트랜잭션 안에서 도므로 읽는 쪽은 지운 상태를 보지 못한다.
 */
@Repository
public class InvalidAnalyticsRepository {

    private static final String DELETE = "DELETE FROM daily_invalid_analytics WHERE business_date = ?";

    private static final String INSERT = """
            INSERT INTO daily_invalid_analytics (
                business_date, reason_code, campaign_id, member_id, invalid_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public InvalidAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, List<InvalidMetrics.Row> rows, LocalDateTime now) {
        Date date = Date.valueOf(businessDate);
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(DELETE, date);
        jdbc.batchUpdate(INSERT, rows, rows.size(), (statement, row) -> {
            statement.setDate(1, date);
            statement.setString(2, row.reasonCode().name());
            statement.setLong(3, row.campaignId());
            statement.setLong(4, row.memberId());
            statement.setLong(5, row.count());
            statement.setTimestamp(6, at);
            statement.setTimestamp(7, at);
        });
    }
}
