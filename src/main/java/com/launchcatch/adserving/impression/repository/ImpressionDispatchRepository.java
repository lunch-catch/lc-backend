package com.launchcatch.adserving.impression.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 정산에 넘길 유효 노출을 찾고 넘긴 결과를 노출 행에 적는다(V8, #1010).
 * dispatched_at 이 비어 있으면 넘겨야 할 노출이다. 무효와 소진 노출은 저장할 때 채워 둔다.
 * 조건의 validity 와 sold_out 은 V8 전의 앱이 저장한 행을 거르는 방어다. 그 행은 dispatched_at 을 채우지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ImpressionDispatchRepository {

    private static final String FIND_PENDING = """
            SELECT serve_id, member_id, campaign_id, business_date
              FROM impression_log
             WHERE dispatched_at IS NULL
               AND created_at >= ?
               AND (next_dispatch_at IS NULL OR next_dispatch_at <= ?)
               AND dispatch_attempts < ?
               AND validity = 'VALID'
               AND sold_out = FALSE
             ORDER BY created_at
             LIMIT ?
            """;

    /*
     * 실패한 노출의 다시 넘길 시각을 random(0, min(5분, 1초 x 2^시도 횟수)) 뒤로 미룬다(Full Jitter, REL-2-06).
     * MySQL 은 SET 을 왼쪽부터 적용하므로 미룰 시간은 올리기 전의 시도 횟수로 센다. 첫 실패는 1초 안이다.
     */
    private static final String BACKOFF_MICROS = "FLOOR(RAND() * LEAST(300000000, 1000000 * POW(2, dispatch_attempts)))";

    private static final String COUNT_PENDING = """
            SELECT COUNT(*) FROM impression_log WHERE dispatched_at IS NULL AND created_at >= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    // idx_imp_dispatch(dispatched_at, created_at) 로 비어 있는 행만 받은 순서대로 읽는다. 실패해 미뤄진 행은 다시 넘길 시각이 지나야 읽는다.
    public List<PendingImpression> findPending(LocalDateTime from, LocalDateTime now, int maxAttempts, int limit) {
        return jdbcTemplate.query(FIND_PENDING, (rs, row) -> new PendingImpression(
                        uuid(rs.getBytes("serve_id")), rs.getLong("member_id"), rs.getLong("campaign_id"),
                        rs.getObject("business_date", LocalDate.class)),
                Timestamp.valueOf(from), Timestamp.valueOf(now), maxAttempts, limit);
    }

    // 정산이 판정을 커밋한 노출에 넘긴 시각을 적는다.
    public void markDispatched(Collection<PendingImpression> impressions, LocalDateTime at) {
        if (impressions.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(at);
        List<Object> args = new ArrayList<>(List.of(now, now));
        args.addAll(keys(impressions));
        jdbcTemplate.update("UPDATE impression_log SET dispatched_at = ?, updated_at = ? WHERE (serve_id, member_id) IN ("
                + placeholders(impressions.size()) + ")", args.toArray());
    }

    // 정산이 실패로 돌려준 노출의 시도 횟수를 올리고 다시 넘길 시각을 미룬다. 상한에 닿으면 findPending 이 더는 읽지 않는다.
    public void markFailed(Collection<PendingImpression> impressions, LocalDateTime at) {
        if (impressions.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.valueOf(at);
        List<Object> args = new ArrayList<>(List.of(now, now));
        args.addAll(keys(impressions));
        jdbcTemplate.update("UPDATE impression_log SET next_dispatch_at = ? + INTERVAL " + BACKOFF_MICROS + " MICROSECOND,"
                + " dispatch_attempts = dispatch_attempts + 1, updated_at = ?"
                + " WHERE (serve_id, member_id) IN (" + placeholders(impressions.size()) + ")", args.toArray());
    }

    // 넘기지 못하고 밀린 노출 수다. 상한에 닿아 건너뛴 행도 센다. 인덱스만 읽는다.
    public long countPending(LocalDateTime from) {
        Long count = jdbcTemplate.queryForObject(COUNT_PENDING, Long.class, Timestamp.valueOf(from));
        return count == null ? 0 : count;
    }

    private static List<Object> keys(Collection<PendingImpression> impressions) {
        List<Object> keys = new ArrayList<>(impressions.size() * 2);
        for (PendingImpression impression : impressions) {
            keys.add(bytes(impression.serveId()));
            keys.add(impression.memberId());
        }
        return keys;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "(?, ?)"));
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public record PendingImpression(UUID serveId, Long memberId, Long campaignId, LocalDate businessDate) {
    }
}
