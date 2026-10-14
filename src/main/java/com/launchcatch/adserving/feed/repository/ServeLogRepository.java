package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.engine.SlotType;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * serve_log 에 피드 한 번의 카드를 한 번의 배치로 쓴다.
 * 키를 앱이 정하는 테이블이라 JPA save 가 행마다 SELECT 를 먼저 보내므로 JDBC 배치를 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class ServeLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public record ServeLogRow(UUID serveId, LocalDate businessDate, UUID feedId, Long memberId, Long campaignId,
                              Long storeId, SlotType slotType, int slotPosition, LocalDateTime servedAt) {
    }

    public void saveAll(List<ServeLogRow> rows, LocalDateTime now) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id,
                                       slot_type, slot_position, served_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, rows.size(), (statement, row) -> {
            statement.setBytes(1, toBytes(row.serveId()));
            statement.setObject(2, row.businessDate());
            statement.setBytes(3, toBytes(row.feedId()));
            statement.setLong(4, row.memberId());
            statement.setLong(5, row.campaignId());
            statement.setLong(6, row.storeId());
            statement.setString(7, row.slotType().name());
            statement.setInt(8, row.slotPosition());
            statement.setObject(9, row.servedAt());
            statement.setObject(10, now);
            statement.setObject(11, now);
        });
    }

    // 상위 64비트가 앞이다. JPA 가 UUID 를 BINARY 로 저장하는 순서와 같다
    static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
