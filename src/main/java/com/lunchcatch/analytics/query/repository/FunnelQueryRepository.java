package com.lunchcatch.analytics.query.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 퍼널 조회(A-03)가 읽는 일별 퍼널 지표다. 조회는 이 자기 집계 테이블만 읽는다(규칙 4).
 */
@Repository
public class FunnelQueryRepository {

    private static final String SELECT = """
            SELECT business_date, valid_impression_count, invalid_impression_count, wish_count,
                   issue_count, redeem_count
            FROM daily_funnel_analytics
            WHERE business_date BETWEEN ? AND ?
            ORDER BY business_date
            """;

    private final JdbcTemplate jdbc;

    public FunnelQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FunnelDailyRow> findBetween(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT,
                (rs, index) -> new FunnelDailyRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("valid_impression_count"), rs.getLong("invalid_impression_count"),
                        rs.getLong("wish_count"), rs.getLong("issue_count"), rs.getLong("redeem_count")),
                Date.valueOf(from), Date.valueOf(to));
    }
}
