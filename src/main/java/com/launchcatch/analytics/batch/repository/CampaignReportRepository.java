package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.CampaignReport;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * campaign_daily_report 와 자식 표 셋(사유, 시간대, 슬롯)의 그날 행을 교체한다.
 *
 * 리포트 행은 지우지 않고 (campaign_id, business_date) 로 갱신해 ID 를 유지한다. 변경 이력(#632)이 그 ID 를 가리키기
 * 때문이다. finalized_at 은 처음 확정한 시각으로 두고 다시 집계해도 바꾸지 않는다. created_at 도 같다.
 * 자식 표는 그날 리포트에 딸린 행을 지우고 다시 넣는다.
 * 교체 전에 확정된 행을 읽고 공개 지표가 바뀐 행의 변경 이력을 campaign_daily_report_history 에 쓴다.
 */
@Repository
public class CampaignReportRepository {

    private static final String UPSERT = """
            INSERT INTO campaign_daily_report (
                campaign_id, owner_id, store_id, business_date, daily_budget, charged_amount, refunded_amount,
                spent_amount, budget_spend_rate, impression_unit_cost, valid_impression_count,
                billable_impression_count, not_billed_impression_count, invalid_impression_count, wish_count,
                issued_count, redeem_count, report_status, finalized_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FINALIZED', ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                owner_id = incoming.owner_id,
                store_id = incoming.store_id,
                daily_budget = incoming.daily_budget,
                charged_amount = incoming.charged_amount,
                refunded_amount = incoming.refunded_amount,
                spent_amount = incoming.spent_amount,
                budget_spend_rate = incoming.budget_spend_rate,
                impression_unit_cost = incoming.impression_unit_cost,
                valid_impression_count = incoming.valid_impression_count,
                billable_impression_count = incoming.billable_impression_count,
                not_billed_impression_count = incoming.not_billed_impression_count,
                invalid_impression_count = incoming.invalid_impression_count,
                wish_count = incoming.wish_count,
                issued_count = incoming.issued_count,
                redeem_count = incoming.redeem_count,
                report_status = 'FINALIZED',
                finalized_at = COALESCE(campaign_daily_report.finalized_at, incoming.finalized_at),
                updated_at = incoming.updated_at
            """;

    private static final String SELECT_IDS = """
            SELECT campaign_daily_report_id, campaign_id FROM campaign_daily_report WHERE business_date = ?
            """;

    private static final String REPORTS_OF_DAY =
            "SELECT campaign_daily_report_id FROM campaign_daily_report WHERE business_date = ?";

    private static final String DELETE_REASONS =
            "DELETE FROM campaign_daily_impression_reason_report WHERE campaign_daily_report_id IN (" + REPORTS_OF_DAY + ")";
    private static final String DELETE_HOURLY =
            "DELETE FROM campaign_hourly_report WHERE campaign_daily_report_id IN (" + REPORTS_OF_DAY + ")";
    private static final String DELETE_SLOTS =
            "DELETE FROM campaign_daily_slot_report WHERE campaign_daily_report_id IN (" + REPORTS_OF_DAY + ")";

    private static final String INSERT_REASON = """
            INSERT INTO campaign_daily_impression_reason_report (
                campaign_daily_report_id, impression_type, reason_code, impression_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HOURLY = """
            INSERT INTO campaign_hourly_report (
                campaign_daily_report_id, report_hour, impression_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            """;
    private static final String INSERT_SLOT = """
            INSERT INTO campaign_daily_slot_report (
                campaign_daily_report_id, slot_type, impression_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            """;

    private static final String FIND_FINALIZED = """
            SELECT r.campaign_daily_report_id, r.campaign_id, r.daily_budget, r.impression_unit_cost, r.charged_amount,
                   r.refunded_amount, r.spent_amount, r.budget_spend_rate, r.valid_impression_count,
                   r.billable_impression_count, r.not_billed_impression_count, r.invalid_impression_count,
                   r.wish_count, r.issued_count, r.redeem_count,
                   (SELECT COALESCE(MAX(h.revision_no), 0) FROM campaign_daily_report_history h
                     WHERE h.campaign_daily_report_id = r.campaign_daily_report_id) AS last_revision
              FROM campaign_daily_report r
             WHERE r.business_date = ? AND r.report_status = 'FINALIZED'
            """;

    /*
     * 변경 전후 JSON 의 키는 명세의 일별 응답 필드 이름이고 값은 metricValues 가 같은 순서로 채운다.
     * SQL 을 조립하지 않고 그대로 적는다. 문자열을 끼워 만들면 정적 분석이 SQL 주입 의심으로 막는다.
     */
    private static final String INSERT_REVISION = """
            INSERT INTO campaign_daily_report_history (
                campaign_daily_report_id, revision_no, change_reason, before_data, after_data, created_at, updated_at
            ) VALUES (?, ?, ?,
                JSON_OBJECT(
                    'dailyBudget', ?, 'impressionUnitCost', ?, 'chargedAmount', ?, 'refundedAmount', ?,
                    'spentAmount', ?, 'budgetSpendRate', ?, 'validImpressionCount', ?, 'billableImpressionCount', ?,
                    'notBilledImpressionCount', ?, 'invalidImpressionCount', ?, 'wishCount', ?, 'issuedCount', ?,
                    'redeemCount', ?),
                JSON_OBJECT(
                    'dailyBudget', ?, 'impressionUnitCost', ?, 'chargedAmount', ?, 'refundedAmount', ?,
                    'spentAmount', ?, 'budgetSpendRate', ?, 'validImpressionCount', ?, 'billableImpressionCount', ?,
                    'notBilledImpressionCount', ?, 'invalidImpressionCount', ?, 'wishCount', ?, 'issuedCount', ?,
                    'redeemCount', ?),
                ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public CampaignReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, List<CampaignReport.Row> rows, LocalDateTime now) {
        Date date = Date.valueOf(businessDate);
        Timestamp at = Timestamp.valueOf(now);
        jdbc.batchUpdate(UPSERT, rows, rows.size(), (statement, row) -> {
            statement.setLong(1, row.campaignId());
            statement.setLong(2, row.ownerId());
            statement.setLong(3, row.storeId());
            statement.setDate(4, date);
            statement.setLong(5, row.dailyBudget());
            statement.setLong(6, row.chargedAmount());
            statement.setLong(7, row.refundedAmount());
            statement.setLong(8, row.spentAmount());
            statement.setBigDecimal(9, row.budgetSpendRate());
            statement.setLong(10, row.unitPrice());
            statement.setLong(11, row.validCount());
            statement.setLong(12, row.billableCount());
            statement.setLong(13, row.notBilledCount());
            statement.setLong(14, row.invalidCount());
            statement.setLong(15, row.wishCount());
            statement.setLong(16, row.issuedCount());
            statement.setLong(17, row.redeemCount());
            statement.setTimestamp(18, at);
            statement.setTimestamp(19, at);
            statement.setTimestamp(20, at);
        });

        jdbc.update(DELETE_REASONS, date);
        jdbc.update(DELETE_HOURLY, date);
        jdbc.update(DELETE_SLOTS, date);

        Map<Long, Long> reportIds = new HashMap<>();
        jdbc.query(SELECT_IDS, rs -> {
            reportIds.put(rs.getLong("campaign_id"), rs.getLong("campaign_daily_report_id"));
        }, date);

        List<Object[]> reasons = new ArrayList<>();
        List<Object[]> hourly = new ArrayList<>();
        List<Object[]> slots = new ArrayList<>();
        for (CampaignReport.Row row : rows) {
            long reportId = reportIds.get(row.campaignId());
            row.notBilledReasons().forEach((reason, count) ->
                    reasons.add(new Object[] {reportId, "UNBILLED", reason.name(), count, at, at}));
            row.invalidReasons().forEach((reason, count) ->
                    reasons.add(new Object[] {reportId, "INVALID", reason.name(), count, at, at}));
            row.hourlyCounts().forEach((hour, count) -> hourly.add(new Object[] {reportId, hour, count, at, at}));
            row.slotCounts().forEach((slot, count) -> slots.add(new Object[] {reportId, slot.name(), count, at, at}));
        }
        jdbc.batchUpdate(INSERT_REASON, reasons);
        jdbc.batchUpdate(INSERT_HOURLY, hourly);
        jdbc.batchUpdate(INSERT_SLOT, slots);
    }

    // 그날 확정된 리포트를 캠페인 ID 로 돌려준다
    public Map<Long, CampaignReport.Finalized> findFinalized(LocalDate businessDate) {
        Map<Long, CampaignReport.Finalized> finalized = new HashMap<>();
        jdbc.query(FIND_FINALIZED, rs -> {
            CampaignReport.Metrics metrics = new CampaignReport.Metrics(
                    rs.getLong("daily_budget"), rs.getLong("impression_unit_cost"), rs.getLong("charged_amount"),
                    rs.getLong("refunded_amount"), rs.getLong("spent_amount"), rs.getBigDecimal("budget_spend_rate"),
                    rs.getLong("valid_impression_count"), rs.getLong("billable_impression_count"),
                    rs.getLong("not_billed_impression_count"), rs.getLong("invalid_impression_count"),
                    rs.getLong("wish_count"), rs.getLong("issued_count"), rs.getLong("redeem_count"));
            finalized.put(rs.getLong("campaign_id"), new CampaignReport.Finalized(
                    rs.getLong("campaign_daily_report_id"), rs.getInt("last_revision"), metrics));
        }, Date.valueOf(businessDate));
        return finalized;
    }

    public void insertRevisions(List<CampaignReport.Revision> revisions, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        List<Object[]> rows = new ArrayList<>();
        for (CampaignReport.Revision revision : revisions) {
            List<Object> values = new ArrayList<>(List.of(
                    revision.reportId(), revision.revisionNo(), revision.reason().name()));
            values.addAll(metricValues(revision.before()));
            values.addAll(metricValues(revision.after()));
            values.add(at);
            values.add(at);
            rows.add(values.toArray());
        }
        jdbc.batchUpdate(INSERT_REVISION, rows);
    }

    private static List<Object> metricValues(CampaignReport.Metrics metrics) {
        return List.of(metrics.dailyBudget(), metrics.unitPrice(), metrics.chargedAmount(), metrics.refundedAmount(),
                metrics.spentAmount(), metrics.budgetSpendRate(), metrics.validCount(), metrics.billableCount(),
                metrics.notBilledCount(), metrics.invalidCount(), metrics.wishCount(), metrics.issuedCount(),
                metrics.redeemCount());
    }
}
