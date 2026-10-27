package com.lunchcatch.adserving.feed.repository;


import com.lunchcatch.global.config.ClockConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * serve_log 를 묶어서 쓴다. 초당 수천 행이라 엔티티를 거치지 않고 JDBC 배치 INSERT 로 쓴다.
 * 같은 serve_id 가 다시 오면 INSERT IGNORE 로 넘긴다. serve_id 는 서버가 만든 UUIDv7 이라 겹치는 일은 재시도뿐이다.
 */
@Repository
@RequiredArgsConstructor
public class ServeLogRepository {

    private static final String INSERT = """
            INSERT IGNORE INTO serve_log (serve_id, business_date, feed_id, member_id, campaign_id, store_id,
                slot_type, slot_position, progress_rate, relevance_score, engine_version, served_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public void insertAll(List<Row> rows) {
        jdbcTemplate.batchUpdate(INSERT, rows, rows.size(), (ps, row) -> {
            ServeRecord r = row.record();
            Timestamp servedAt = Timestamp.valueOf(LocalDateTime.ofInstant(r.servedAt(), ClockConfig.ZONE));
            ps.setBytes(1, bytes(r.serveId()));
            ps.setObject(2, row.businessDate());
            ps.setBytes(3, bytes(row.feedId()));
            ps.setLong(4, r.memberId());
            ps.setLong(5, r.campaignId());
            ps.setLong(6, r.storeId());
            ps.setString(7, r.slotType());
            ps.setInt(8, r.slotPosition());
            ps.setObject(9, row.progressRate() == null ? null : BigDecimal.valueOf(row.progressRate()).setScale(4, RoundingMode.HALF_UP));
            ps.setBigDecimal(10, BigDecimal.valueOf(row.relevanceScore()).setScale(4, RoundingMode.HALF_UP));
            ps.setString(11, row.engineVersion());
            ps.setTimestamp(12, servedAt);
            ps.setTimestamp(13, servedAt);
            ps.setTimestamp(14, servedAt);
        });
    }

    /*
     * 보관 기간이 지난 영업일의 행을 limit 개까지 지운다.
     * 문장마다 따로 커밋한다(#610).
     */
    public int deleteBusinessDateBefore(LocalDate before, int limit) {
        return jdbcTemplate.update("DELETE FROM serve_log WHERE business_date < ? LIMIT ?", before, limit);
    }

    static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    /*
     * serve_log 한 행이다. 영업일, 묶음 ID, 엔진 버전은 카드마다 같아 피드 단위로 붙인다.
     * progressRate 와 relevanceScore 는 배정 근거이고 넷째 자리에서 반올림해 남긴다(#977).
     */
    public record Row(LocalDate businessDate, UUID feedId, ServeRecord record, Double progressRate, double relevanceScore,
                      String engineVersion) {
    }
}
