package com.launchcatch.analytics.query.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 가게 지표 조회(A-02)가 읽는 일별 가게 지표다. 조회는 이 자기 집계 테이블만 읽는다(규칙 4).
 */
@Repository
public class StoreMetricsQueryRepository {

    private static final String SELECT = """
            SELECT business_date, new_store_count, cumulative_store_count, active_campaign_count
            FROM daily_store_analytics
            WHERE business_date BETWEEN ? AND ?
            ORDER BY business_date
            """;

    private final JdbcTemplate jdbc;

    public StoreMetricsQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StoreMetricsDailyRow> findBetween(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT,
                (rs, index) -> new StoreMetricsDailyRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("new_store_count"), rs.getLong("cumulative_store_count"),
                        rs.getLong("active_campaign_count")),
                Date.valueOf(from), Date.valueOf(to));
    }
}
