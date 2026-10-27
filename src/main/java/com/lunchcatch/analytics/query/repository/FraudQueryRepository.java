package com.lunchcatch.analytics.query.repository;

import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.query.InvalidImpressionRow;
import com.lunchcatch.analytics.query.InvalidImpressionSort;
import com.lunchcatch.analytics.query.ReasonTotal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 부정 관리 조회(A-09, A-10)가 읽는 일별 집계다. 조회는 이 자기 집계 테이블만 읽는다(규칙 4).
 *
 * 정렬 열과 방향은 열거형과 불리언으로만 받아 SQL 에 붙인다. 요청 문자열은 쿼리에 들어가지 못한다.
 */
@Repository
public class FraudQueryRepository {

    private static final String INVALID_GROUP = "GROUP BY member_id, campaign_id, reason_code";

    /*
     * 캠페인마다 기간 합계 한 행이다.
     * 점유율이 가장 높았던 하루(같으면 늦은 날)의 값을 함께 읽는다
     */
    private static final String CAMPAIGN_FRAUD = """
            SELECT campaign_id, total_valid, total_invalid, distinct_member_count, top_share, any_suspected
            FROM (
                SELECT campaign_id,
                       SUM(valid_count) OVER (PARTITION BY campaign_id) AS total_valid,
                       SUM(invalid_count) OVER (PARTITION BY campaign_id) AS total_invalid,
                       distinct_member_count,
                       CASE WHEN valid_count = 0 THEN 0 ELSE top_members_count / valid_count END AS top_share,
                       MAX(concentration_suspected) OVER (PARTITION BY campaign_id) AS any_suspected,
                       ROW_NUMBER() OVER (
                           PARTITION BY campaign_id
                           ORDER BY CASE WHEN valid_count = 0 THEN 0 ELSE top_members_count / valid_count END DESC,
                                    business_date DESC) AS rank_in_campaign
                FROM daily_campaign_fraud_analytics
                WHERE business_date BETWEEN ? AND ?
            ) ranked
            WHERE rank_in_campaign = 1
            ORDER BY campaign_id
            """;

    private final JdbcTemplate jdbc;

    public FraudQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InvalidImpressionRow> findInvalid(
            FraudFilter filter, InvalidImpressionSort sort, boolean descending, int offset, int limit) {
        Where where = where(filter);
        String sql = "SELECT member_id, campaign_id, reason_code, SUM(invalid_count) AS total_count "
                + "FROM daily_invalid_analytics " + where.sql() + " " + INVALID_GROUP
                + " ORDER BY " + sort.column() + (descending ? " DESC" : " ASC")
                + ", member_id, campaign_id, reason_code LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(where.args());
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql,
                (rs, index) -> new InvalidImpressionRow(rs.getLong("member_id"), rs.getLong("campaign_id"),
                        InvalidReasonCode.valueOf(rs.getString("reason_code")), rs.getLong("total_count")),
                args.toArray());
    }

    public long countInvalid(FraudFilter filter) {
        Where where = where(filter);
        String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM daily_invalid_analytics " + where.sql() + " "
                + INVALID_GROUP + ") grouped";
        Long count = jdbc.queryForObject(sql, Long.class, where.args().toArray());
        return count == null ? 0 : count;
    }

    public List<ReasonTotal> sumInvalidByReason(FraudFilter filter) {
        Where where = where(filter);
        String sql = "SELECT reason_code, SUM(invalid_count) AS total_count FROM daily_invalid_analytics "
                + where.sql() + " GROUP BY reason_code";
        return jdbc.query(sql,
                (rs, index) -> new ReasonTotal(InvalidReasonCode.valueOf(rs.getString("reason_code")),
                        rs.getLong("total_count")),
                where.args().toArray());
    }

    public List<CampaignFraudRow> findCampaignFraud(LocalDate from, LocalDate to) {
        return jdbc.query(CAMPAIGN_FRAUD,
                (rs, index) -> new CampaignFraudRow(rs.getLong("campaign_id"), rs.getLong("total_valid"),
                        rs.getLong("total_invalid"), rs.getLong("distinct_member_count"),
                        rs.getDouble("top_share"), rs.getInt("any_suspected") != 0),
                Date.valueOf(from), Date.valueOf(to));
    }

    private Where where(FraudFilter filter) {
        StringBuilder sql = new StringBuilder("WHERE business_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(filter.range().from()));
        args.add(Date.valueOf(filter.range().to()));
        if (filter.reasonCode() != null) {
            sql.append(" AND reason_code = ?");
            args.add(filter.reasonCode().name());
        }
        if (filter.campaignId() != null) {
            sql.append(" AND campaign_id = ?");
            args.add(filter.campaignId());
        }
        if (filter.memberId() != null) {
            sql.append(" AND member_id = ?");
            args.add(filter.memberId());
        }
        return new Where(sql.toString(), args);
    }

    private record Where(String sql, List<Object> args) {
    }
}
