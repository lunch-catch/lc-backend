package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.ServeRecord;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 피드 응답 카드별 기록(serve_log)을 쓴다.
 * 판정과 과금에 쓰이지 않고 관리자 진단이 읽는다
 */
@Repository
@RequiredArgsConstructor
public class ServeLogRepository {

    private static final String INSERT_SQL = """
            INSERT INTO serve_log
                (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type, slot_position,
                 served_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    // 피드 한 번의 카드를 한 번의 배치로 쓴다
    public void saveAll(List<ServeRecord> records, LocalDateTime now) {
        jdbcTemplate.batchUpdate(INSERT_SQL, records, records.size(), (ps, record) -> {
            ps.setBytes(1, toBytes(record.serveId()));
            ps.setObject(2, record.businessDate());
            ps.setBytes(3, toBytes(record.feedId()));
            ps.setLong(4, record.memberId());
            ps.setLong(5, record.campaignId());
            ps.setLong(6, record.storeId());
            ps.setString(7, record.slotType().name());
            ps.setInt(8, record.slotPosition());
            ps.setObject(9, record.servedAt());
            ps.setObject(10, now);
            ps.setObject(11, now);
        });
    }

    // BINARY(16) 컬럼에 UUID 를 상위 64비트부터 담는다
    static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
