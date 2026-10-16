package com.launchcatch.billing.repository;

import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    // 여러 점주의 잔액을 한 번에 구하며 원장이 없는 점주는 결과에 없다
    @Query("""
            select new com.launchcatch.billing.repository.OwnerBalanceRow(l.ownerId, sum(l.amount))
              from PointLedger l
             where l.ownerId in :ownerIds
               and l.entryType <> com.launchcatch.billing.entity.EntryType.DEDUCT
             group by l.ownerId
            """)
    List<OwnerBalanceRow> sumBalanceByOwnerIds(@Param("ownerIds") Collection<Long> ownerIds);

    // 같은 점주가 같은 멱등 키로 남긴 조정이다(uk_point_ledger_adjust_idem)
    Optional<PointLedger> findByOwnerIdAndIdempotencyKey(Long ownerId, String idempotencyKey);

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

    /*
     * 위와 같은 합을 캠페인 여러 개에 대해 한 번에 구한다. 그날 DEDUCT 가 없는 캠페인은 결과에 없다(소진 0).
     * 해시가 한꺼번에 사라진 직후의 진행률 조회가 캠페인마다 쿼리를 내지 않게 한다.
     */
    @Query("""
            select new com.launchcatch.billing.dto.CampaignSpend(l.campaignId, -sum(l.amount))
              from PointLedger l, DeductionResult d
             where d.serveId = l.serveId
               and l.entryType = com.launchcatch.billing.entity.EntryType.DEDUCT
               and l.campaignId in :campaignIds
               and d.businessDate = :businessDate
             group by l.campaignId
            """)
    List<CampaignSpend> sumDeductForCampaignsOn(@Param("campaignIds") Collection<Long> campaignIds,
                                                @Param("businessDate") LocalDate businessDate);

    /*
     * 집계 조회가 쓰는 점주와 캠페인과 유형별 합이다. 양수 행과 음수 행을 나눠 더해 ADJUST 의 방향을 살린다.
     * RESERVE 와 RELEASE 는 원장의 영업일로 묻는다.
     */
    @Query("""
            select new com.launchcatch.billing.repository.LedgerSumRow(l.ownerId, l.campaignId, l.entryType,
                       sum(case when l.amount > 0 then l.amount else 0L end),
                       sum(case when l.amount < 0 then l.amount else 0L end))
              from PointLedger l
             where l.businessDate = :businessDate
               and l.entryType in (com.launchcatch.billing.entity.EntryType.RESERVE,
                                   com.launchcatch.billing.entity.EntryType.RELEASE)
             group by l.ownerId, l.campaignId, l.entryType
            """)
    List<LedgerSumRow> sumReserveReleaseOn(@Param("businessDate") LocalDate businessDate);

    // DEDUCT 는 영업일이 차감 판정에 있어 serve_id 로 맞춰 묻는다
    @Query("""
            select new com.launchcatch.billing.repository.LedgerSumRow(l.ownerId, l.campaignId, l.entryType,
                       sum(case when l.amount > 0 then l.amount else 0L end),
                       sum(case when l.amount < 0 then l.amount else 0L end))
              from PointLedger l, DeductionResult d
             where d.serveId = l.serveId
               and l.entryType = com.launchcatch.billing.entity.EntryType.DEDUCT
               and d.businessDate = :businessDate
             group by l.ownerId, l.campaignId, l.entryType
            """)
    List<LedgerSumRow> sumDeductOn(@Param("businessDate") LocalDate businessDate);

    // CHARGE, REFUND, ADJUST 는 영업일 컬럼이 없어 기록 시각 [from, to) 로 묻는다
    @Query("""
            select new com.launchcatch.billing.repository.LedgerSumRow(l.ownerId, l.campaignId, l.entryType,
                       sum(case when l.amount > 0 then l.amount else 0L end),
                       sum(case when l.amount < 0 then l.amount else 0L end))
              from PointLedger l
             where l.createdAt >= :from
               and l.createdAt < :to
               and l.entryType in (com.launchcatch.billing.entity.EntryType.CHARGE,
                                   com.launchcatch.billing.entity.EntryType.REFUND,
                                   com.launchcatch.billing.entity.EntryType.ADJUST)
             group by l.ownerId, l.campaignId, l.entryType
            """)
    List<LedgerSumRow> sumChargeRefundAdjustBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /*
     * 전 점주의 미소진 포인트다. before 이전에 기록된 CHARGE, REFUND, ADJUST, DEDUCT 의 합이다.
     * 점주 잔액에 예약 중 포인트(예약액 - 소진 - 해제)를 더한 값과 같다. RESERVE 와 RELEASE 는 그 둘 사이에서 상쇄되어 세지 않는다.
     */
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.entryType in (com.launchcatch.billing.entity.EntryType.CHARGE,
                                   com.launchcatch.billing.entity.EntryType.REFUND,
                                   com.launchcatch.billing.entity.EntryType.ADJUST,
                                   com.launchcatch.billing.entity.EntryType.DEDUCT)
               and l.createdAt < :before
            """)
    long sumUnspentBefore(@Param("before") LocalDateTime before);

    // 기록 시각이 [from, to) 인 미소진 포인트 변동의 합이다. 위와 같은 네 유형을 센다
    @Query("""
            select coalesce(sum(l.amount), 0L)
              from PointLedger l
             where l.entryType in (com.launchcatch.billing.entity.EntryType.CHARGE,
                                   com.launchcatch.billing.entity.EntryType.REFUND,
                                   com.launchcatch.billing.entity.EntryType.ADJUST,
                                   com.launchcatch.billing.entity.EntryType.DEDUCT)
               and l.createdAt >= :from
               and l.createdAt < :to
            """)
    long sumUnspentBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 그 영업일에 RELEASE 가 이미 있는 캠페인이다. 점주 ID 로 걸러 idx_point_ledger_owner_business_date 를 탄다
    @Query("""
            select distinct l.campaignId
              from PointLedger l
             where l.ownerId in :ownerIds
               and l.businessDate = :businessDate
               and l.entryType = com.launchcatch.billing.entity.EntryType.RELEASE
            """)
    List<Long> findReleasedCampaignIds(@Param("ownerIds") Collection<Long> ownerIds,
                                       @Param("businessDate") LocalDate businessDate);

    /*
     * 캠페인 하나의 그 영업일 RESERVE 를 없을 때만 넣는다. 같은 캠페인과 영업일의 RESERVE 가 이미 있으면 그대로 둔다
     * (day_reserve_dedup_key UNIQUE). 00:00 예약은 점주 계정 행을 잠근 뒤 계획이 없는 캠페인만 쓰지만, 잠금 밖에서 같은 행을
     * 쓰는 경로가 생겨도 UNIQUE 위반으로 단계가 멈추지 않게 중복을 값을 바꾸지 않는 갱신으로 흡수한다(구현 계획 D-5).
     * 금액은 음수로 받는다(chk_point_ledger_amount_sign). 배치가 쓰므로 source 는 RECONCILIATION 이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_ledger
                (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
            VALUES
                (:ownerId, :campaignId, :businessDate, 'RESERVE', 'RECONCILIATION', :amount, :now, :now)
            ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id
            """, nativeQuery = true)
    int insertReserveIfAbsent(@Param("ownerId") Long ownerId,
                              @Param("campaignId") Long campaignId,
                              @Param("businessDate") LocalDate businessDate,
                              @Param("amount") long amount,
                              @Param("now") LocalDateTime now);

    /*
     * 캠페인 하나의 그 영업일 RELEASE 를 없을 때만 넣는다. 같은 캠페인과 영업일의 RELEASE 가 이미 있으면 그대로 둔다
     * (day_reserve_dedup_key UNIQUE). 이어받기로 두 실행이 겹쳐도 한쪽이 UNIQUE 위반으로 실패하지 않는다.
     * 중복은 값을 바꾸지 않는 갱신으로 흡수하고 INSERT IGNORE 는 쓰지 않는다(구현 계획 D-5).
     * 금액은 양수다(chk_point_ledger_amount_sign). 배치가 쓰므로 source 는 RECONCILIATION 이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_ledger
                (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
            VALUES
                (:ownerId, :campaignId, :businessDate, 'RELEASE', 'RECONCILIATION', :amount, :now, :now)
            ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id
            """, nativeQuery = true)
    int insertReleaseIfAbsent(@Param("ownerId") Long ownerId,
                              @Param("campaignId") Long campaignId,
                              @Param("businessDate") LocalDate businessDate,
                              @Param("amount") long amount,
                              @Param("now") LocalDateTime now);

    /*
     * 충전 직후 재예약 한 건을 없을 때만 넣는다(I-03). 같은 결제와 캠페인의 재예약이 이미 있으면 그대로 둔다
     * (uk_point_ledger_recharge_reserve_dedup). 중복은 값을 바꾸지 않는 갱신으로 흡수하고 INSERT IGNORE 는
     * 쓰지 않는다(구현 계획 D-5). 금액은 음수로 받는다(chk_point_ledger_amount_sign).
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_ledger
                (owner_id, payment_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
            VALUES
                (:ownerId, :paymentId, :campaignId, :businessDate, 'RESERVE', 'RECONCILIATION', :amount, :now, :now)
            ON DUPLICATE KEY UPDATE point_ledger_id = point_ledger_id
            """, nativeQuery = true)
    int insertRechargeReserveIfAbsent(@Param("ownerId") Long ownerId,
                                      @Param("paymentId") Long paymentId,
                                      @Param("campaignId") Long campaignId,
                                      @Param("businessDate") LocalDate businessDate,
                                      @Param("amount") long amount,
                                      @Param("now") LocalDateTime now);
}
