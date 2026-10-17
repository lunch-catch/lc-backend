package com.launchcatch.analytics.query.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 매출 조회(A-07)가 읽는 일별 정산 지표다. 조회는 이 자기 집계 테이블만 읽는다(규칙 4).
 */
@Repository
public class SalesQueryRepository {

    private static final String SELECT = """
            SELECT business_date, charge_amount, deduct_amount, refund_amount, adjust_amount,
                   positive_adjust_amount, unspent_balance
            FROM daily_settlement_analytics
            WHERE business_date BETWEEN ? AND ?
            ORDER BY business_date
            """;

    private final JdbcTemplate jdbc;

    public SalesQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SalesDailyRow> findBetween(LocalDate from, LocalDate to) {
        return jdbc.query(SELECT,
                (rs, index) -> new SalesDailyRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("charge_amount"), rs.getLong("deduct_amount"),
                        rs.getLong("refund_amount"), rs.getLong("adjust_amount"),
                        rs.getLong("positive_adjust_amount"), rs.getLong("unspent_balance")),
                Date.valueOf(from), Date.valueOf(to));
    }
}
