package com.launchcatch.analytics.query.repository;

import com.launchcatch.analytics.query.CampaignReportTotal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CampaignReportQueryRepository {

    private final JdbcTemplate jdbc;

    public CampaignReportQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * 캠페인마다 기간의 소진액과 유효 노출을 합한다. 기간이 null 이면 그 캠페인의 모든 행을 합한다.
     * 행이 없는 캠페인은 결과에 없다. 0 으로 채우는 일은 서비스가 한다.
     * 확정된 행만 합한다. 집계 중(AGGREGATING) 행은 확정 전 값이라 소진액에 넣지 않는다(#895).
     */
    public List<CampaignReportTotal> sumByCampaign(List<Long> campaignIds, LocalDate from, LocalDate to) {
        String placeholders = String.join(", ", Collections.nCopies(campaignIds.size(), "?"));
        StringBuilder sql = new StringBuilder("""
                SELECT campaign_id, SUM(spent_amount) AS spent_amount,
                       SUM(valid_impression_count) AS valid_impression_count
                FROM campaign_daily_report
                WHERE campaign_id IN (""").append(placeholders).append(") AND report_status = 'FINALIZED'");
        List<Object> args = new ArrayList<>(campaignIds);
        if (from != null) {
            sql.append(" AND business_date BETWEEN ? AND ?");
            args.add(Date.valueOf(from));
            args.add(Date.valueOf(to));
        }
        sql.append(" GROUP BY campaign_id");
        return jdbc.query(sql.toString(),
                (rs, index) -> new CampaignReportTotal(rs.getLong("campaign_id"), rs.getLong("spent_amount"),
                        rs.getLong("valid_impression_count")),
                args.toArray());
    }
}
