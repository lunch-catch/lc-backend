package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.entity.SwipeLog;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

// 중복 저장을 PK 로 거부하되 예외로 깨지지 않도록 INSERT IGNORE 로 쓴다. JPA 의 persist 는 같은 키에서 제약 위반을 던진다.
@RequiredArgsConstructor
class SwipeLogInsertImpl implements SwipeLogInsert {

    private static final String INSERT = """
            INSERT IGNORE INTO swipe_log (serve_id, member_id, business_date, campaign_id, category_code, action,
                occurred_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    public boolean insertIgnore(SwipeLog log) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        return jdbcTemplate.update(INSERT, bytes(log.getId().getServeId()), log.getId().getMemberId(),
                log.getBusinessDate(), log.getCampaignId(), log.getCategoryCode(), log.getAction().name(),
                Timestamp.valueOf(log.getOccurredAt()), now, now) == 1;
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
