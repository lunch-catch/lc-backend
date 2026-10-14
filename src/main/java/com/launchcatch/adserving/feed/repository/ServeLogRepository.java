package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.ServeRecord;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 피드 응답 뒤에 서빙 로그와 그날 첫 요청 위치를 쓴다. 피드 한 번의 카드를 한 번에 넣는다.
@Repository
@RequiredArgsConstructor
public class ServeLogRepository {

    private static final String INSERT_SERVE_LOG = """
            INSERT INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id,
                                   slot_type, slot_position, served_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // 그날 첫 요청 위치 1건만 남긴다. 두 번째부터는 PK 에 걸려 무시된다.
    private static final String INSERT_VISIT = """
            INSERT IGNORE INTO feed_visit_daily (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public void saveAll(List<ServeRecord> records, LocalDateTime now) {
        Timestamp createdAt = Timestamp.valueOf(now);
        jdbcTemplate.batchUpdate(INSERT_SERVE_LOG, records, records.size(), (ps, record) -> {
            ps.setBytes(1, toBytes(record.serveId()));
            ps.setObject(2, record.businessDate());
            ps.setBytes(3, toBytes(record.feedId()));
            ps.setLong(4, record.memberId());
            ps.setLong(5, record.campaignId());
            ps.setLong(6, record.storeId());
            ps.setString(7, record.slotType().name());
            ps.setInt(8, record.slotPosition());
            ps.setTimestamp(9, Timestamp.valueOf(record.servedAt()));
            ps.setTimestamp(10, createdAt);
            ps.setTimestamp(11, createdAt);
        });
    }

    public void insertVisitIfAbsent(LocalDate businessDate, Long memberId, BigDecimal latRounded,
            BigDecimal lngRounded, LocalDateTime now) {
        Timestamp createdAt = Timestamp.valueOf(now);
        jdbcTemplate.update(INSERT_VISIT, businessDate, memberId, latRounded, lngRounded, createdAt, createdAt);
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
