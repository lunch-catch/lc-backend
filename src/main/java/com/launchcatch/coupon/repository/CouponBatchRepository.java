package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.config.CouponProperties;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CouponBatchRepository {

    private final CouponProperties properties;

    private final JdbcTemplate jdbc;

    /*
     * 한 회차의 수량이 많아도 JDBC에 한꺼번에 넘기는 재고는 설정된 크기로 제한한다.
     * 이 메서드는 중간 커밋을 하지 않으며 회차를 저장한 서비스의 트랜잭션에 참여한다.
     * OFFSET 없이 순번을 직접 만들어 재시도 시 새 회차의 순번이 1부터 연속되게 한다.
     */
    public void prepareInventory(long eventId, int quantity, LocalDateTime start, LocalDateTime end,
            LocalDateTime now) {
        for (int from = 1; from <= quantity; from += properties.batch().pageSize()) {
            List<Integer> sequences = IntStream.rangeClosed(from, Math.min(from + properties.batch().pageSize() - 1, quantity)).boxed().toList();
            jdbc.batchUpdate("""
                    INSERT INTO coupon_inventory
                        (coupon_event_id, sequence_no, coupon_code, status, usable_start_at, usable_end_at,
                         created_at, updated_at)
                    VALUES (?, ?, ?, 'AVAILABLE', ?, ?, ?, ?)
                    """, sequences, properties.batch().pageSize(), (statement, sequence) -> {
                UUID code = UUID.randomUUID();
                statement.setLong(1, eventId);
                statement.setInt(2, sequence);
                statement.setBytes(3, ByteBuffer.allocate(16)
                        .putLong(code.getMostSignificantBits()).putLong(code.getLeastSignificantBits()).array());
                statement.setObject(4, start);
                statement.setObject(5, end);
                statement.setObject(6, now);
                statement.setObject(7, now);
            });
        }
    }

    public List<Long> eventIds(LocalDate date, long afterId) {
        return jdbc.queryForList("""
                SELECT coupon_event_id FROM coupon_event
                 WHERE business_date = ? AND coupon_event_id > ? ORDER BY coupon_event_id LIMIT ?
                """, Long.class, date, afterId, properties.batch().pageSize());
    }

    public int eventCount(LocalDate date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon_event WHERE business_date = ?", Integer.class, date);
    }

    /*
     * 상태 변경으로 앞 페이지의 ISSUED 행이 사라지므로 OFFSET을 쓰면 다음 대상을 건너뛸 수 있다.
     * 마지막 ID보다 큰 행을 설정된 크기로 조회해 만료 처리 중에도 남은 대상을 빠뜨리지 않는다.
     */
    public List<Long> expiringIds(LocalDate date, LocalDateTime cutoff, long afterId) {
        return jdbc.queryForList("""
                SELECT coupon.member_coupon_id
                  FROM member_coupon coupon JOIN coupon_event event USING (coupon_event_id)
                 WHERE event.business_date = ? AND coupon.member_coupon_id > ?
                   AND coupon.status = 'ISSUED' AND coupon.usable_end_at <= ?
                 ORDER BY coupon.member_coupon_id LIMIT ?
                """, Long.class, date, afterId, cutoff, properties.batch().pageSize());
    }

    // 실제 발급된 회원 쿠폰의 상태와 종료 시각으로 대상을 정하고 회차의 영업일로 실행 이력을 묶는다.
    public List<LocalDate> expiringDates(LocalDateTime now) {
        return jdbc.query("""
                SELECT DISTINCT event.business_date
                  FROM member_coupon coupon JOIN coupon_event event USING (coupon_event_id)
                 WHERE coupon.status = 'ISSUED' AND coupon.usable_end_at <= ?
                 ORDER BY event.business_date
                """, (row, index) -> row.getDate(1).toLocalDate(), now);
    }
}
