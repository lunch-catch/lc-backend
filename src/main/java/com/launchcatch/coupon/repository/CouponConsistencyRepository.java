package com.launchcatch.coupon.repository;

import static com.launchcatch.coupon.CouponBatchTask.BATCH_SIZE;

import com.launchcatch.coupon.CouponConsistencyFinding;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CouponConsistencyRepository {

    private final JdbcTemplate jdbc;

    /*
     * 회차·재고·회원 쿠폰은 영업일을 기준으로 대조하고 일일 한도는 실제 발급일을 기준으로 집계한다.
     * 삭제한 쿠폰은 발급 사실을 보존하므로 재고 대조에는 포함하되 일일 한도에서만 제외한다.
     * 유형과 대상 ID의 조합은 검증 항목마다 유일하므로 정렬한 결과를 빠짐없이 이어 읽을 수 있다.
     */
    public List<CouponConsistencyFinding> findings(LocalDate date, String afterType, long afterId, LocalDateTime checkedAt) {
        return jdbc.query("""
                WITH scoped_events AS (
                    SELECT * FROM coupon_event WHERE business_date = ?
                ), scoped_coupons AS (
                    SELECT coupon.* FROM member_coupon coupon JOIN scoped_events event USING (coupon_event_id)
                ), counters AS (
                    SELECT member_id, COUNT(*) actual_value FROM member_coupon
                     WHERE created_at >= ? AND created_at < ? AND status <> 'REMOVED' GROUP BY member_id
                ), counter_members AS (
                    SELECT member_id FROM counters
                    UNION SELECT member_id FROM coupon_daily_limit WHERE business_date = ?
                ), findings AS (
                    SELECT 'INVENTORY_COUNT' issue_type, event.coupon_event_id target_id,
                           event.issue_quantity expected_value, COUNT(inventory.coupon_inventory_id) actual_value
                      FROM scoped_events event LEFT JOIN coupon_inventory inventory USING (coupon_event_id)
                     GROUP BY event.coupon_event_id, event.issue_quantity
                    HAVING expected_value <> actual_value
                    UNION ALL
                    SELECT 'INVENTORY_FIRST', event.coupon_event_id, 1, COALESCE(MIN(inventory.sequence_no), 0)
                      FROM scoped_events event LEFT JOIN coupon_inventory inventory USING (coupon_event_id)
                     GROUP BY event.coupon_event_id HAVING COALESCE(MIN(inventory.sequence_no), 0) <> 1
                    UNION ALL
                    SELECT 'INVENTORY_LAST', event.coupon_event_id, event.issue_quantity,
                           COALESCE(MAX(inventory.sequence_no), 0)
                      FROM scoped_events event LEFT JOIN coupon_inventory inventory USING (coupon_event_id)
                     GROUP BY event.coupon_event_id, event.issue_quantity
                    HAVING event.issue_quantity <> COALESCE(MAX(inventory.sequence_no), 0)
                    UNION ALL
                    SELECT 'ISSUED_INVENTORY', inventory.coupon_inventory_id, 1, 0
                      FROM coupon_inventory inventory JOIN scoped_events event USING (coupon_event_id)
                      LEFT JOIN member_coupon coupon ON coupon.coupon_code = inventory.coupon_code
                     WHERE inventory.status = 'ISSUED'
                       AND (coupon.member_coupon_id IS NULL OR coupon.coupon_event_id <> inventory.coupon_event_id)
                    UNION ALL
                    SELECT 'ISSUED_COUPON', coupon.member_coupon_id, 1, 0
                      FROM scoped_coupons coupon
                      LEFT JOIN coupon_inventory inventory ON inventory.coupon_code = coupon.coupon_code
                     WHERE inventory.coupon_inventory_id IS NULL OR inventory.status <> 'ISSUED'
                        OR inventory.coupon_event_id <> coupon.coupon_event_id
                    UNION ALL
                    SELECT 'DAILY_COUNTER', subject.member_id, COALESCE(actual.actual_value, 0),
                           COALESCE(counter.issued_count, 0)
                      FROM counter_members subject LEFT JOIN counters actual USING (member_id)
                      LEFT JOIN coupon_daily_limit counter ON counter.member_id = subject.member_id
                           AND counter.business_date = ?
                     WHERE counter.member_id IS NULL OR COALESCE(counter.issued_count, 0) <> COALESCE(actual.actual_value, 0)
                    UNION ALL
                    SELECT 'USAGE_HISTORY', coupon.member_coupon_id,
                           CASE WHEN coupon.status = 'USED' THEN 1 ELSE 0 END,
                           COUNT(history.coupon_usage_history_id)
                      FROM scoped_coupons coupon
                      LEFT JOIN coupon_usage_history history USING (member_coupon_id)
                     GROUP BY coupon.member_coupon_id, coupon.status
                    HAVING COUNT(history.coupon_usage_history_id) <> CASE WHEN coupon.status = 'USED' THEN 1 ELSE 0 END
                    UNION ALL
                    SELECT 'USAGE_STORE', history.coupon_usage_history_id, event.store_id, history.store_id
                      FROM coupon_usage_history history JOIN scoped_coupons coupon USING (member_coupon_id)
                      JOIN scoped_events event USING (coupon_event_id) WHERE history.store_id <> event.store_id
                    UNION ALL
                    SELECT 'COUPON_START_TIME', coupon.member_coupon_id, 1, 0
                      FROM scoped_coupons coupon JOIN scoped_events event USING (coupon_event_id)
                     WHERE coupon.usable_start_at <> event.usable_start_at OR coupon.usable_end_at <> event.usable_end_at
                    UNION ALL
                    SELECT 'INVENTORY_TIME', inventory.coupon_inventory_id, 1, 0
                      FROM coupon_inventory inventory JOIN scoped_events event USING (coupon_event_id)
                     WHERE inventory.usable_start_at <> event.usable_start_at OR inventory.usable_end_at <> event.usable_end_at
                    UNION ALL
                    SELECT 'STORE_EVENT_COUNT', store_id, 1, COUNT(*) FROM scoped_events GROUP BY store_id HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT 'EXPIRATION_STATUS', coupon.member_coupon_id, 1, 0 FROM scoped_coupons coupon
                     WHERE (coupon.status = 'ISSUED' AND coupon.usable_end_at <= ?)
                        OR (coupon.status = 'EXPIRED' AND coupon.usable_end_at > ?)
                )
                SELECT issue_type, target_id, expected_value, actual_value FROM findings
                 WHERE issue_type > ? OR (issue_type = ? AND target_id > ?)
                 ORDER BY issue_type, target_id LIMIT ?
                """, (row, index) -> new CouponConsistencyFinding(row.getString(1), row.getLong(2),
                        row.getLong(3), row.getLong(4)),
                date, date.atStartOfDay(), date.plusDays(1).atStartOfDay(), date, date,
                checkedAt, checkedAt, afterType, afterType, afterId, BATCH_SIZE);
    }

}
