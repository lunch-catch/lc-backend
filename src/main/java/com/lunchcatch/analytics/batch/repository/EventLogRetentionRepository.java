package com.lunchcatch.analytics.batch.repository;

import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/*
 * 보존 기간이 지난 event_log 행을 지운다. 한 번에 지우는 행 수를 제한해 락을 오래 쥐지 않는다.
 *
 * occurred_at 의 단독 인덱스(idx_event_log_occurred)를 타므로 오래된 행부터 잘라낸다.
 */
@Repository
public class EventLogRetentionRepository {

    private final JdbcTemplate jdbc;

    public EventLogRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * cutoff 보다 이전에 일어난 이벤트를 최대 limit 행 지우고 지운 행 수를 돌려준다.
     * cutoff 는 LocalDateTime 그대로 넘긴다. Timestamp 로 바꾸면 드라이버가 JVM 시간대에서 connectionTimeZone 으로
     * 옮겨, JVM 이 UTC 일 때 삭제 선이 9시간 늦어진다.
     */
    public int deleteOlderThan(LocalDateTime cutoff, int limit) {
        return jdbc.update("DELETE FROM event_log WHERE occurred_at < ? LIMIT ?", cutoff, limit);
    }
}
