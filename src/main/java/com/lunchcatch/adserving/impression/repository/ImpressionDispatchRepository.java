package com.lunchcatch.adserving.impression.repository;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
            SELECT serve_id, member_id, campaign_id, business_date, created_at
              FROM impression_log
             WHERE dispatched_at IS NULL
               AND created_at >= ?
               AND (next_dispatch_at IS NULL OR next_dispatch_at <= ?)
               AND dispatch_abandoned_at IS NULL
               AND validity = 'VALID'
               AND sold_out = FALSE
             ORDER BY created_at
             LIMIT ?
            """;

    // 키 목록(:keys)은 NamedParameterJdbcTemplate 이 ((?, ?), (?, ?), ...) 로 펼친다. SQL 문자열은 상수로 둔다.
    private static final String MARK_DISPATCHED = """
            UPDATE impression_log
               SET dispatched_at = :at, updated_at = :at
             WHERE (serve_id, member_id) IN (:keys)
               AND dispatched_at IS NULL
            """;

    /*
     * 실패한 노출의 다시 넘길 시각을 random(0, min(5분, 1초 x 2^시도 횟수)) 뒤로 미룬다(Full Jitter, REL-2-06).
     * MySQL 은 SET 을 왼쪽부터 적용하므로 미룰 시간은 올리기 전의 시도 횟수로 센다. 첫 실패는 1초 안이다.
     */
    private static final String MARK_FAILED = """
            UPDATE impression_log
               SET next_dispatch_at = :at + INTERVAL FLOOR(RAND() * LEAST(300000000, 1000000 * POW(2, dispatch_attempts))) MICROSECOND,
                   dispatch_attempts = dispatch_attempts + 1,
                   dispatch_abandoned_at = IF(dispatch_attempts >= :maxAttempts, :at, NULL),
                   updated_at = :at
             WHERE (serve_id, member_id) IN (:keys)
               AND dispatched_at IS NULL
            """;

    /*
     * 넘기지 않은 노출을 같은 인덱스(dispatched_at, created_at)로 한 번에 읽어 넷으로 가른다.
     * 밀린 수는 읽는 범위 안에서 포기하지 않은 노출이다. 다시 넘길 시각을 기다리는 노출도 든다.
     * 가장 이른 시각은 그 가운데 지금 넘길 수 있는 노출만 본다. 미뤄진 노출 하나로 대기 시간이 늘지 않게 한다.
     * 포기한 노출은 범위와 상관없이 모두 센다. 읽는 범위보다 오래된 미발행은 마감이 지나 넘기지 않는 노출이다.
     */
    private static final String COUNT_PENDING = """
            SELECT COALESCE(SUM(created_at >= :from AND dispatch_abandoned_at IS NULL), 0),
                   MIN(CASE WHEN created_at >= :from AND dispatch_abandoned_at IS NULL
                                 AND (next_dispatch_at IS NULL OR next_dispatch_at <= :now) THEN created_at END),
                   COALESCE(SUM(dispatch_abandoned_at IS NOT NULL), 0),
                   COALESCE(SUM(created_at < :from AND dispatch_abandoned_at IS NULL), 0)
              FROM impression_log
             WHERE dispatched_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    // idx_imp_dispatch(dispatched_at, created_at) 로 비어 있는 행만 받은 순서대로 읽는다. 실패해 미뤄진 행은 다시 넘길 시각이 지나야 읽는다.
    public List<PendingImpression> findPending(LocalDateTime from, LocalDateTime now, int limit) {
        return jdbcTemplate.query(FIND_PENDING, (rs, row) -> new PendingImpression(
                        uuid(rs.getBytes("serve_id")), rs.getLong("member_id"), rs.getLong("campaign_id"),
                        rs.getObject("business_date", LocalDate.class), rs.getObject("created_at", LocalDateTime.class)),
                Timestamp.valueOf(from), Timestamp.valueOf(now), limit);
    }

    // 정산이 판정을 커밋한 노출에 넘긴 시각을 적는다.
    /*
     * 아직 넘기지 않은 노출에만 넘긴 시각을 적고 적은 행 수를 돌려준다.
     * 임대가 풀려 다른 배치 서버가 같은 노출을 먼저 넘겼으면 그 시각을 덮어쓰지 않는다(#1213).
     */
    public int markDispatched(Collection<PendingImpression> impressions, LocalDateTime at) {
        if (impressions.isEmpty()) {
            return 0;
        }
        return namedJdbcTemplate.update(MARK_DISPATCHED, params(impressions, at));
    }

    /*
     * 정산이 실패로 돌려준 노출의 시도 횟수를 올리고 다시 넘길 시각을 미룬다. 상한에 닿으면 findPending 이 더는 읽지 않는다.
     * 다른 서버가 이미 넘긴 노출은 건드리지 않는다(#1213).
     */
    public void markFailed(Collection<PendingImpression> impressions, LocalDateTime at, int maxAttempts) {
        if (impressions.isEmpty()) {
            return;
        }
        namedJdbcTemplate.update(MARK_FAILED, params(impressions, at).addValue("maxAttempts", maxAttempts));
    }

    /*
     * 넘기지 못하고 밀린 노출 수와 그 가운데 가장 이른 저장 시각이다. 상한에 닿아 건너뛴 행도 센다. 인덱스만 읽는다.
     * 밀린 것이 없으면 시각은 null 이다.
     */
    public PendingSummary countPending(LocalDateTime from, LocalDateTime now) {
        // DATETIME 을 시간대 변환 없이 LocalDateTime 으로 읽는다. Timestamp 를 거치면 JVM 의 시간대로 바뀌어 대기 시간이 어긋난다
        return namedJdbcTemplate.queryForObject(COUNT_PENDING,
                new MapSqlParameterSource().addValue("from", Timestamp.valueOf(from)).addValue("now", Timestamp.valueOf(now)),
                (rs, n) -> new PendingSummary(rs.getLong(1), rs.getObject(2, LocalDateTime.class), rs.getLong(3), rs.getLong(4)));
    }

    private static MapSqlParameterSource params(Collection<PendingImpression> impressions, LocalDateTime at) {
        List<Object[]> keys = impressions.stream()
                .map(impression -> new Object[] {bytes(impression.serveId()), impression.memberId()})
                .toList();
        return new MapSqlParameterSource().addValue("at", Timestamp.valueOf(at)).addValue("keys", keys);
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    // createdAt 은 노출 요청을 받은 시각이다(저장 시각이 아니다). 넘긴 시각과의 차이가 발행 지연이라 노출 저장 대기열에서 기다린 시간도 든다
    public record PendingImpression(UUID serveId, Long memberId, Long campaignId, LocalDate businessDate,
                                    LocalDateTime createdAt) {
    }

    /*
     * count 는 밀린 수, oldest 는 지금 넘길 수 있는 노출 가운데 가장 이른 저장 시각이다.
     * abandoned 는 포기한 노출, expired 는 읽는 범위보다 오래돼 넘기지 않는 미발행이다.
     */
    public record PendingSummary(long count, LocalDateTime oldest, long abandoned, long expired) {
    }
}
