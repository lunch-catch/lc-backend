package com.launchcatch.analytics.batch.repository;

import com.launchcatch.analytics.batch.PendingReason;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 성공할 때까지 다시 처리할 영업일이다(aggregation_pending_date).
 *
 * 같은 날짜를 두 서버가 함께 남길 수 있어 INSERT ... ON DUPLICATE KEY UPDATE 로 쓴다. 예외로
 * 중복을 받으면 부른 트랜잭션이 롤백 표시가 된다.
 */
@Repository
public class PendingDateRepository {

    private static final String UPSERT = """
            INSERT INTO aggregation_pending_date (business_date, reason, recorded_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?) AS incoming
            ON DUPLICATE KEY UPDATE
                reason = incoming.reason,
                recorded_at = incoming.recorded_at,
                updated_at = incoming.updated_at
            """;

    private final JdbcTemplate jdbc;

    public PendingDateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(LocalDate businessDate, PendingReason reason, LocalDateTime now) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update(UPSERT, Date.valueOf(businessDate), reason.name(), at, at, at);
    }

    public List<LocalDate> dates() {
        return jdbc.queryForList(
                "SELECT business_date FROM aggregation_pending_date ORDER BY business_date",
                LocalDate.class);
    }

    public void remove(LocalDate businessDate) {
        jdbc.update("DELETE FROM aggregation_pending_date WHERE business_date = ?",
                Date.valueOf(businessDate));
    }

    public int removeBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM aggregation_pending_date WHERE business_date < ?",
                Date.valueOf(cutoff));
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM aggregation_pending_date", Long.class);
        return count == null ? 0 : count;
    }
}
