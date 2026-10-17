package com.launchcatch.analytics.batch.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 일별 정산 지표(daily_settlement_analytics)를 쓰고 읽는다.
 *
 * 그날 행은 한 문장으로 교체한다(INSERT ... ON DUPLICATE KEY UPDATE). 지우고 넣으면 그 사이를 다른
 * 트랜잭션이 보지 못해도 두 문장이 되고, 예외로 중복을 받으면 부른 트랜잭션이 롤백 표시가 된다.
 * created_at 은 처음 만든 시각으로 두고 갱신하지 않는다.
 */
@Repository
public class SettlementAnalyticsRepository {

    private static final String UPSERT = """
            INSERT INTO daily_settlement_analytics (
                business_date, charge_amount, deduct_amount, reserved_amount, released_amount,
                refund_amount, adjust_amount, positive_adjust_amount, unspent_balance,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                charge_amount = incoming.charge_amount,
                deduct_amount = incoming.deduct_amount,
                reserved_amount = incoming.reserved_amount,
                released_amount = incoming.released_amount,
                refund_amount = incoming.refund_amount,
                adjust_amount = incoming.adjust_amount,
                positive_adjust_amount = incoming.positive_adjust_amount,
                unspent_balance = incoming.unspent_balance,
                updated_at = incoming.updated_at
            """;

    private static final String SELECT = """
            SELECT charge_amount, deduct_amount, reserved_amount, released_amount, refund_amount,
                   adjust_amount, positive_adjust_amount, unspent_balance
            FROM daily_settlement_analytics
            WHERE business_date = ?
            """;

    private final JdbcTemplate jdbc;

    public SettlementAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(LocalDate businessDate, SettlementAnalyticsRow row, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(UPSERT, Date.valueOf(businessDate), row.chargeAmount(), row.deductAmount(),
                row.reservedAmount(), row.releasedAmount(), row.refundAmount(), row.adjustAmount(),
                row.positiveAdjustAmount(), row.unspentBalance(), at, at);
    }

    public Optional<SettlementAnalyticsRow> find(LocalDate businessDate) {
        List<SettlementAnalyticsRow> rows = jdbc.query(SELECT,
                (rs, index) -> new SettlementAnalyticsRow(
                        rs.getLong("charge_amount"), rs.getLong("deduct_amount"),
                        rs.getLong("reserved_amount"), rs.getLong("released_amount"),
                        rs.getLong("refund_amount"), rs.getLong("adjust_amount"),
                        rs.getLong("positive_adjust_amount"), rs.getLong("unspent_balance")),
                Date.valueOf(businessDate));
        return rows.stream().findFirst();
    }
}
