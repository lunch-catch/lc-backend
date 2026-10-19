package com.launchcatch.coupon.repository;

import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponEventRepository extends JpaRepository<CouponEvent, Long> {

    // 요청한 매장의 오늘 조회 대상 이벤트를 한 번에 읽으며 상태나 재고를 변경하지 않는다.
    List<CouponEvent> findByStoreIdInAndBusinessDateAndStatusIn(
            List<Long> storeIds, LocalDate businessDate, List<CouponEventStatus> statuses);

    // 이벤트가 소유한 재고를 상태별로 집계하며 재고 행을 잠그지 않는다.
    @Query("""
            SELECT inventory.couponEventId AS eventId, COUNT(inventory) AS quantity
              FROM CouponInventory inventory
             WHERE inventory.couponEventId IN :eventIds AND inventory.status = :status
             GROUP BY inventory.couponEventId
            """)
    List<InventoryQuantity> findQuantitiesByEventIdsAndStatus(
            @Param("eventIds") List<Long> eventIds, @Param("status") CouponInventoryStatus status);

    interface InventoryQuantity {
        Long getEventId();
        long getQuantity();
    }

    // 네 수량을 같은 스냅샷에서 각각 집계해 재고와 사용 이력의 조인으로 수량이 중복되지 않게 한다.
    @Query(value = """
            SELECT
                (SELECT COALESCE(SUM(event.issue_quantity), 0)
                   FROM coupon_event event
                  WHERE event.campaign_id = :campaignId AND event.business_date BETWEEN :from AND :to) AS totalQuantity,
                (SELECT COUNT(coupon.member_coupon_id)
                   FROM member_coupon coupon
                   JOIN coupon_event event ON event.coupon_event_id = coupon.coupon_event_id
                  WHERE event.campaign_id = :campaignId AND event.business_date BETWEEN :from AND :to) AS issuedCount,
                (SELECT COUNT(history.coupon_usage_history_id)
                   FROM coupon_usage_history history
                   JOIN member_coupon coupon ON coupon.member_coupon_id = history.member_coupon_id
                   JOIN coupon_event event ON event.coupon_event_id = coupon.coupon_event_id
                  WHERE event.campaign_id = :campaignId
                    AND history.created_at >= :fromInclusive AND history.created_at < :toExclusive) AS usedCount,
                (SELECT COUNT(inventory.coupon_inventory_id)
                   FROM coupon_inventory inventory
                   JOIN coupon_event event ON event.coupon_event_id = inventory.coupon_event_id
                  WHERE event.campaign_id = :campaignId AND event.business_date BETWEEN :from AND :to
                    AND inventory.status = 'AVAILABLE') AS remainingQuantity
            """, nativeQuery = true)
    OwnerSummary findOwnerSummary(@Param("campaignId") long campaignId,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("fromInclusive") LocalDateTime fromInclusive, @Param("toExclusive") LocalDateTime toExclusive);

    interface OwnerSummary {
        long getTotalQuantity();
        long getIssuedCount();
        long getUsedCount();
        long getRemainingQuantity();
    }

    // 회차별 수치와 실제 사용일의 수치를 각각 묶어 조인으로 발급·오픈 수가 늘지 않게 한다.
    @Query(value = """
            SELECT stats.campaign_id AS campaignId,
                   SUM(stats.open_count) AS openCount,
                   SUM(stats.issued_count) AS issuedCount,
                   SUM(stats.used_count) AS usedCount,
                   SUM(stats.expired_count) AS expiredCount
              FROM (
                    SELECT ce.campaign_id,
                           COUNT(DISTINCT ce.coupon_event_id) AS open_count,
                           COUNT(mc.member_coupon_id) AS issued_count,
                           0 AS used_count,
                           SUM(CASE WHEN mc.status = 'EXPIRED' THEN 1 ELSE 0 END) AS expired_count
                      FROM coupon_event ce
                      LEFT JOIN member_coupon mc ON mc.coupon_event_id = ce.coupon_event_id
                     WHERE ce.business_date = :businessDate
                     GROUP BY ce.campaign_id
                    UNION ALL
                    SELECT ce.campaign_id, 0, 0, COUNT(history.coupon_usage_history_id), 0
                      FROM coupon_usage_history history
                      JOIN member_coupon mc ON mc.member_coupon_id = history.member_coupon_id
                      JOIN coupon_event ce ON ce.coupon_event_id = mc.coupon_event_id
                     WHERE history.created_at >= :fromInclusive AND history.created_at < :toExclusive
                     GROUP BY ce.campaign_id
                   ) stats
             GROUP BY stats.campaign_id
             ORDER BY stats.campaign_id
            """, nativeQuery = true)
    List<CampaignStats> findDailyCampaignStats(@Param("businessDate") LocalDate businessDate,
            @Param("fromInclusive") LocalDateTime fromInclusive, @Param("toExclusive") LocalDateTime toExclusive);

    interface CampaignStats {
        long getCampaignId();
        long getOpenCount();
        long getIssuedCount();
        long getUsedCount();
        long getExpiredCount();
    }
}
