package com.launchcatch.coupon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;

// 통합 검증과 성능 측정에 같은 MySQL 재고를 준비한다.
final class CouponIssuanceFixture {

    private final JdbcTemplate jdbc;
    private final LocalDate today;

    CouponIssuanceFixture(JdbcTemplate jdbc, LocalDate today) {
        this.jdbc = jdbc;
        this.today = today;
    }

    void event(long eventId, int quantity) {
        LocalDateTime now = today.atTime(12, 0);
        jdbc.update("""
                INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                    usable_start_at, usable_end_at, status, store_id, store_name,
                    discount_target_type, discount_type, discount_value, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 10, '발급 시험 매장', 'ALL', 'AMOUNT', 3000, ?, ?)
                """, eventId, eventId, today, quantity, today.atTime(11, 0), today.atTime(14, 0), now, now);
        jdbc.batchUpdate("""
                INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                    usable_start_at, usable_end_at, created_at, updated_at)
                VALUES (?, ?, UNHEX(MD5(?)), 'AVAILABLE', ?, ?, ?, ?)
                """, IntStream.rangeClosed(1, quantity).mapToObj(sequence -> new Object[] {
                    eventId, sequence, eventId + ":" + sequence, today.atTime(11, 0), today.atTime(14, 0), now, now
                }).toList());
    }

    void clear() {
        jdbc.update("DELETE FROM coupon_usage_history");
        jdbc.update("DELETE FROM member_coupon");
        jdbc.update("DELETE FROM coupon_inventory");
        jdbc.update("DELETE FROM coupon_daily_limit");
        jdbc.update("DELETE FROM coupon_event");
    }
}
