package com.lunchcatch.analytics.query.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 회원 지표 조회(A-01)가 읽는 일별 회원 지표다. 조회는 이 자기 집계 테이블만 읽는다(규칙 4).
 */
@Repository
public class MemberMetricsQueryRepository {

    private static final String SELECT = """
            SELECT business_date, new_member_count, cumulative_member_count, active_member_count,
                   withdrawn_count, suspended_count
            FROM daily_member_analytics
            WHERE business_date BETWEEN ? AND ?
            ORDER BY business_date
            """;

    private final JdbcTemplate jdbc;

    public MemberMetricsQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MemberMetricsDailyRow> findBetween(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT,
                (rs, index) -> new MemberMetricsDailyRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("new_member_count"), rs.getLong("cumulative_member_count"),
                        rs.getLong("active_member_count"), rs.getLong("withdrawn_count"),
                        rs.getLong("suspended_count")),
                Date.valueOf(from), Date.valueOf(to));
    }
}
