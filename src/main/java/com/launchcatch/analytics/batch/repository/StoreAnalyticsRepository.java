package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.StoreMetrics;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 가게 지표(daily_store_analytics)를 쓴다.
 *
 * 그날 행은 한 문장으로 교체한다(INSERT ... ON DUPLICATE KEY UPDATE). created_at 은 처음 만든 시각으로
 * 두고 갱신하지 않는다.
 */
@Repository
public class StoreAnalyticsRepository {

    private static final String UPSERT = """
            INSERT INTO daily_store_analytics (
                business_date, new_store_count, cumulative_store_count, active_campaign_count,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                new_store_count = incoming.new_store_count,
                cumulative_store_count = incoming.cumulative_store_count,
                active_campaign_count = incoming.active_campaign_count,
                updated_at = incoming.updated_at
            """;

    private final JdbcTemplate jdbc;

    public StoreAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, StoreMetrics metrics, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(UPSERT, Date.valueOf(businessDate), metrics.newStoreCount(),
                metrics.cumulativeStoreCount(), metrics.activeCampaignCount(), at, at);
    }
}
