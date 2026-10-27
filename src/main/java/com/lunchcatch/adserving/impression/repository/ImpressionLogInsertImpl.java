package com.lunchcatch.adserving.impression.repository;

import com.lunchcatch.adserving.impression.entity.ImpressionLog;
import com.lunchcatch.adserving.impression.entity.ImpressionLogId;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/*
 * 중복 저장을 PK 로 거부하되 예외로 트랜잭션을 깨지 않도록 INSERT IGNORE 로 쓴다(V1 의 impression_log 주석).
 * JPA 의 persist 는 같은 키에서 제약 위반을 던져 트랜잭션 전체를 되돌린다.
 * 정산에 넘기지 않을 노출은 dispatched_at 을 받은 시각으로 채운다. 비어 있는 행만 릴레이가 넘긴다(V8).
 */
@RequiredArgsConstructor
class ImpressionLogInsertImpl implements ImpressionLogInsert {

    private static final String INSERT = """
            INSERT IGNORE INTO impression_log (serve_id, member_id, business_date, campaign_id, store_id, slot_type,
                slot_position, displayed_at, lat_rounded, lng_rounded, validity, invalid_reason, sold_out, viewed_ms,
                dispatched_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // 키 목록(:keys)은 NamedParameterJdbcTemplate 이 ((?, ?), (?, ?), ...) 로 펼친다. SQL 문자열은 상수로 둔다.
    private static final String FIND_CREATED_AT = """
            SELECT serve_id, member_id, created_at FROM impression_log WHERE (serve_id, member_id) IN (:keys)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /*
     * 배치 INSERT 는 rewriteBatchedStatements 로 한 문장이 되어 행마다의 결과를 돌려주지 않는다.
     * 그래서 넣은 뒤 같은 트랜잭션에서 그 키들의 created_at 을 읽어 이 묶음이 넘긴 받은 시각과 같은 행을 새로 들어간 것으로 본다.
     * 먼저 저장된 행은 다른 요청이 받은 시각을 갖고 있어 마이크로초까지 같을 일이 없다.
     * 같은 키가 묶음 안에 둘이면 먼저 받은 것만 넘긴다.
     */
    @Override
    public Set<ImpressionLogId> insertAllIgnore(List<Row> rows) {
        Map<ImpressionLogId, Row> unique = new LinkedHashMap<>();
        rows.forEach(row -> unique.putIfAbsent(row.log().getId(), row));
        List<Row> distinct = List.copyOf(unique.values());
        jdbcTemplate.batchUpdate(INSERT, distinct, distinct.size(), (ps, row) -> {
            ImpressionLog log = row.log();
            Timestamp receivedAt = Timestamp.valueOf(row.receivedAt());
            ps.setBytes(1, bytes(log.getId().getServeId()));
            ps.setLong(2, log.getId().getMemberId());
            ps.setObject(3, log.getBusinessDate());
            ps.setLong(4, log.getCampaignId());
            ps.setObject(5, log.getStoreId());
            ps.setString(6, log.getSlotType());
            ps.setObject(7, log.getSlotPosition());
            ps.setTimestamp(8, Timestamp.valueOf(log.getDisplayedAt()));
            ps.setBigDecimal(9, log.getLatRounded());
            ps.setBigDecimal(10, log.getLngRounded());
            ps.setString(11, log.getValidity().name());
            ps.setString(12, log.getInvalidReason() == null ? null : log.getInvalidReason().name());
            ps.setBoolean(13, log.isSoldOut());
            ps.setObject(14, log.getViewedMs());
            ps.setTimestamp(15, log.needsDispatch() ? null : receivedAt);
            ps.setTimestamp(16, receivedAt);
            ps.setTimestamp(17, receivedAt);
        });
        List<Object[]> keys = distinct.stream()
                .map(row -> new Object[] {bytes(row.log().getId().getServeId()), row.log().getId().getMemberId()})
                .toList();
        Set<ImpressionLogId> inserted = new HashSet<>();
        namedJdbcTemplate.query(FIND_CREATED_AT, new MapSqlParameterSource("keys", keys), rs -> {
            ImpressionLogId id = ImpressionLogId.of(uuid(rs.getBytes("serve_id")), rs.getLong("member_id"));
            LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            if (createdAt.equals(unique.get(id).receivedAt())) {
                inserted.add(id);
            }
        });
        return inserted;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
