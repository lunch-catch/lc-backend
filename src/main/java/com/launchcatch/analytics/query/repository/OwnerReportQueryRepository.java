package com.launchcatch.analytics.query.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerReportQueryRepository {

    private static final String SELECT = """
            SELECT business_date, campaign_id, daily_budget, impression_unit_cost,
                   valid_impression_count, invalid_impression_count, wish_count,
                   issued_count, redeem_count, charged_amount, refunded_amount, spent_amount
            FROM campaign_daily_report
            WHERE owner_id = ? AND business_date BETWEEN ? AND ?
            ORDER BY business_date, campaign_id
            """;

    private final JdbcTemplate jdbc;

    public OwnerReportQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * 리포트에 저장된 점주 ID로 소유 범위를 제한하고 시작일과 종료일을 포함해 조회한다.
     * 결과는 영업일과 캠페인 ID 오름차순이며 집계가 없는 날짜의 행은 만들지 않는다.
     */
    public List<OwnerReportDailyRow> findBetween(long ownerId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT,
                (rs, index) -> new OwnerReportDailyRow(
                        rs.getObject("business_date", LocalDate.class),
                        rs.getLong("campaign_id"), rs.getLong("daily_budget"),
                        rs.getLong("impression_unit_cost"), rs.getLong("valid_impression_count"),
                        rs.getLong("invalid_impression_count"), rs.getLong("wish_count"),
                        rs.getLong("issued_count"), rs.getLong("redeem_count"),
                        rs.getLong("charged_amount"), rs.getLong("refunded_amount"),
                        rs.getLong("spent_amount")),
                ownerId, Date.valueOf(from), Date.valueOf(to));
    }
}
