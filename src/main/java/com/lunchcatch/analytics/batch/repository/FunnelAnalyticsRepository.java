package com.lunchcatch.analytics.batch.repository;

import com.lunchcatch.analytics.batch.FunnelMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 퍼널 지표(daily_funnel_analytics)를 쓴다.
 *
 * 그날 행은 한 문장으로 교체한다(INSERT ... ON DUPLICATE KEY UPDATE). created_at 은 처음 만든 시각으로
 * 두고 갱신하지 않는다.
 */
@Repository
public class FunnelAnalyticsRepository {

    private static final String UPSERT = """
            INSERT INTO daily_funnel_analytics (
                business_date, valid_impression_count, invalid_impression_count, wish_count,
                issue_count, redeem_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                valid_impression_count = incoming.valid_impression_count,
                invalid_impression_count = incoming.invalid_impression_count,
                wish_count = incoming.wish_count,
                issue_count = incoming.issue_count,
                redeem_count = incoming.redeem_count,
                updated_at = incoming.updated_at
            """;

    private final JdbcTemplate jdbc;

    public FunnelAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, FunnelMetrics metrics, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(UPSERT, Date.valueOf(businessDate), metrics.validImpressionCount(),
                metrics.invalidImpressionCount(), metrics.wishCount(), metrics.issueCount(),
                metrics.redeemCount(), at, at);
    }
}
