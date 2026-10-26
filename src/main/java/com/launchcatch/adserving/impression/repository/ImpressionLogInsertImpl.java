package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean insertIgnore(ImpressionLog log, LocalDateTime receivedAt) {
        Timestamp now = Timestamp.valueOf(receivedAt);
        return jdbcTemplate.update(INSERT, bytes(log.getId().getServeId()), log.getId().getMemberId(),
                log.getBusinessDate(), log.getCampaignId(), log.getStoreId(),
                log.getSlotType(), log.getSlotPosition(),
                Timestamp.valueOf(log.getDisplayedAt()), log.getLatRounded(), log.getLngRounded(),
                log.getValidity().name(), log.getInvalidReason() == null ? null : log.getInvalidReason().name(),
                log.isSoldOut(), log.getViewedMs(), log.needsDispatch() ? null : now, now, now) == 1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
