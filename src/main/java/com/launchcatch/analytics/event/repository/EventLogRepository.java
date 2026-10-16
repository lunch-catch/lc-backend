package com.launchcatch.analytics.event.repository;

import com.launchcatch.analytics.event.entity.EventLogEntry;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EventLogRepository {

    private static final String INSERT = """
            INSERT INTO event_log (
                event_id, event_type, member_id, campaign_id, store_id, serve_id,
                wish_id, issue_id, coupon_event_id, member_coupon_id,
                occurred_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public EventLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(EventLogEntry entry) {
        try {
            jdbc.update(INSERT, statement -> bind(statement, entry));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private void bind(PreparedStatement statement, EventLogEntry entry) throws SQLException {
        statement.setString(1, entry.eventId().toString());
        statement.setString(2, entry.eventType().value());
        setNullableLong(statement, 3, entry.memberId());
        setNullableLong(statement, 4, entry.campaignId());
        setNullableLong(statement, 5, entry.storeId());
        setNullableBytes(statement, 6, entry.serveId());
        setNullableLong(statement, 7, entry.wishId());
        setNullableLong(statement, 8, entry.couponEventId());
        setNullableLong(statement, 9, entry.memberCouponId());
        statement.setTimestamp(10, Timestamp.valueOf(entry.occurredAt()));
        statement.setTimestamp(11, Timestamp.valueOf(entry.createdAt()));
        statement.setTimestamp(12, Timestamp.valueOf(entry.updatedAt()));
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }
        statement.setLong(index, value);
    }

    private void setNullableBytes(PreparedStatement statement, int index, java.util.UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BINARY);
            return;
        }
        statement.setBytes(index, ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array());
    }
}
