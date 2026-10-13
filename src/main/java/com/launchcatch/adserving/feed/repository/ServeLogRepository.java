package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.ServedFeed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 피드 응답 뒤에 비동기로 쓰는 두 기록이다. serve_log 는 카드마다 한 행이고 feed_visit_daily 는 (영업일, 회원)마다 한 행이다.
 * 행을 처음 만드는 쓰기뿐이라 엔티티를 거치지 않는다. 키를 직접 정한 엔티티는 저장 전에 행마다 SELECT 를 낸다.
 */
@Repository
@RequiredArgsConstructor
public class ServeLogRepository {

    private static final String INSERT_SERVE_LOG_SQL = """
            INSERT INTO serve_log
                (serve_id, business_date, feed_id, member_id, campaign_id, store_id, slot_type, slot_position,
                 served_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /*
     * 그날 첫 요청 위치만 남긴다.
     * 두 번째 요청부터는 PK 에 걸려 무시된다.
     */
    private static final String INSERT_VISIT_SQL = """
            INSERT IGNORE INTO feed_visit_daily
                (business_date, member_id, lat_rounded, lng_rounded, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final int COORDINATE_SCALE = 3;

    private final JdbcTemplate jdbcTemplate;

    public void saveServeLogs(ServedFeed feed) {
        Timestamp servedAt = Timestamp.valueOf(feed.servedAt());
        jdbcTemplate.batchUpdate(INSERT_SERVE_LOG_SQL, feed.cards(), feed.cards().size(), (ps, served) -> {
            ps.setBytes(1, toBytes(served.serveId()));
            ps.setDate(2, Date.valueOf(feed.businessDate()));
            ps.setBytes(3, toBytes(feed.feedId()));
            ps.setLong(4, feed.memberId());
            ps.setLong(5, served.card().candidate().campaignId());
            ps.setLong(6, served.card().candidate().storeId());
            ps.setString(7, served.card().slotType().name());
            ps.setInt(8, served.card().slotPosition());
            ps.setTimestamp(9, servedAt);
            ps.setTimestamp(10, servedAt);
            ps.setTimestamp(11, servedAt);
        });
    }

    // 좌표는 소수점 셋째 자리로 반올림해 남긴다(약 100m)
    public void saveVisit(ServedFeed feed) {
        Timestamp servedAt = Timestamp.valueOf(feed.servedAt());
        jdbcTemplate.update(INSERT_VISIT_SQL,
                Date.valueOf(feed.businessDate()),
                feed.memberId(),
                round(feed.latitude()),
                round(feed.longitude()),
                servedAt,
                servedAt);
    }

    static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(COORDINATE_SCALE, RoundingMode.HALF_UP);
    }
}
