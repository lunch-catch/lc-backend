package com.launchcatch.billing.repository;

import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * 원장 저장소다. 합계는 모두 부호가 있는 저장값을 그대로 더한 값이고, 행이 없으면 0 이다.
 * 잔액 계산에서 DEDUCT 를 빼는 규칙은 쿼리가 안고 있어서 호출하는 쪽이 같은 규칙을 다시 적지 않는다.
 */
public interface PointLedgerRepository extends JpaRepository<PointLedger, Long>, PointLedgerRepositoryCustom {

    // 잔액이다. 예약 단계에서 이미 빠진 금액이라 DEDUCT 는 더하지 않는다
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.ownerId = :ownerId
               and l.entryType <> com.launchcatch.billing.entity.EntryType.DEDUCT
            """)
    long sumBalance(@Param("ownerId") Long ownerId);

    // 영업일로 귀속되는 RESERVE 와 RELEASE 의 합이다
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.ownerId = :ownerId
               and l.entryType = :entryType
               and l.businessDate = :businessDate
            """)
    long sumByBusinessDate(@Param("ownerId") Long ownerId,
                           @Param("entryType") EntryType entryType,
                           @Param("businessDate") LocalDate businessDate);

    // 기록 시각이 [from, to) 인 행의 합이다. 날짜를 DB 함수로 자르지 않고 범위로 묻는다
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.ownerId = :ownerId
               and l.entryType = :entryType
               and l.createdAt >= :from
               and l.createdAt < :to
            """)
    long sumByCreatedAt(@Param("ownerId") Long ownerId,
                        @Param("entryType") EntryType entryType,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

    // 기간 시작 전의 잔액이다. 변동 전후 잔액 누적의 출발점이 된다
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.ownerId = :ownerId
               and l.entryType <> com.launchcatch.billing.entity.EntryType.DEDUCT
               and l.createdAt < :before
            """)
    long sumBalanceBefore(@Param("ownerId") Long ownerId, @Param("before") LocalDateTime before);

    /*
     * 노출 과금 한 건을 DEDUCT 로 기록한다. 같은 serve_id 의 DEDUCT 가 이미 있으면 그대로 둔다.
     *
     * 금액은 음수로 받는다(-단가). chk_point_ledger_amount_sign 이 DEDUCT 는 음수여야 한다고 강제한다.
     * business_date 는 NULL 로 둔다. chk_point_ledger_business_date 가 DEDUCT 의 영업일을 NULL 로 강제하고,
     * 영업일은 deduction_result 가 갖는다.
     *
     * 중복은 값을 바꾸지 않는 갱신(ON DUPLICATE KEY UPDATE serve_id = serve_id)으로 흡수한다.
     * INSERT IGNORE 는 쓰지 않는다. CHECK 위반까지 경고로 낮추고 행을 조용히 버린다(구현 계획 D-5).
     *
     * 영향받은 행 수는 새로 만들면 1, 이미 있으면 0 이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_ledger
                (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
            VALUES
                (:ownerId, :campaignId, :serveId, 'DEDUCT', 'REALTIME', :amount, :now, :now)
            ON DUPLICATE KEY UPDATE serve_id = serve_id
            """, nativeQuery = true)
    int insertDeductIfAbsent(@Param("ownerId") Long ownerId,
                             @Param("campaignId") Long campaignId,
                             @Param("serveId") byte[] serveId,
                             @Param("amount") long amount,
                             @Param("now") LocalDateTime now);

    // serve_id 를 BINARY(16) 바이트로 바꿔 넘기는 호출 편의 메서드다
    default int insertDeductIfAbsent(Long ownerId, Long campaignId, UUID serveId, long amount, LocalDateTime now) {
        return insertDeductIfAbsent(ownerId, campaignId, ServeIds.toBytes(serveId), amount, now);
    }

    /*
     * 캠페인이 그 영업일에 쓴 금액이다. 부호를 뒤집어 양수로 돌려준다.
     *
     * DEDUCT 행에는 영업일이 없어서 deduction_result 와 serve_id 로 맞춰 영업일로 거른다.
     * 해시 재구성이 Valkey 의 spend 를 다시 채울 때 쓰는 정본 값이다.
     */
    @Query("""
            select coalesce(-sum(l.amount), 0L)
              from PointLedger l, DeductionResult d
             where d.serveId = l.serveId
               and l.entryType = com.launchcatch.billing.entity.EntryType.DEDUCT
               and l.campaignId = :campaignId
               and d.businessDate = :businessDate
            """)
    long sumDeductForCampaignOn(@Param("campaignId") Long campaignId, @Param("businessDate") LocalDate businessDate);
}
