package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.entity.SettlementMismatch;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * 대사 불일치 저장소다.
 * 대사 건은 하루에 캠페인 수 이하로 쌓이고 대부분 배치가 닫으므로 조회 전용 인덱스를 두지 않는다.
 */
public interface SettlementMismatchRepository extends JpaRepository<SettlementMismatch, Long> {

    // 한 건을 읽는다
    @Query("""
            select new com.launchcatch.billing.repository.SettlementMismatchRow(
                       m.id, m.checkType, m.targetId, m.businessDate, m.expectedValue, m.actualValue,
                       m.resolutionType, m.jobExecutionId, m.createdAt, m.resolvedAt, m.resolvedBy)
              from SettlementMismatch m
             where m.id = :id
            """)
    Optional<SettlementMismatchRow> findRowById(@Param("id") Long id);

    /*
     * 관리자 목록이다. 비어 있는 조건은 걸지 않는다.
     * 해결 여부는 두 플래그로 받는다. 둘 다 false 면 해결 여부로 거르지 않는다.
     * 정렬은 호출하는 쪽이 Pageable 에 담는다.
     */
    @Query(value = """
            select new com.launchcatch.billing.repository.SettlementMismatchRow(
                       m.id, m.checkType, m.targetId, m.businessDate, m.expectedValue, m.actualValue,
                       m.resolutionType, m.jobExecutionId, m.createdAt, m.resolvedAt, m.resolvedBy)
              from SettlementMismatch m
             where (:checkType is null or m.checkType = :checkType)
               and (:onlyResolved = false or m.resolutionType is not null)
               and (:onlyUnresolved = false or m.resolutionType is null)
               and (:fromDate is null or m.businessDate >= :fromDate)
               and (:toDate is null or m.businessDate <= :toDate)
            """,
            countQuery = """
            select count(m)
              from SettlementMismatch m
             where (:checkType is null or m.checkType = :checkType)
               and (:onlyResolved = false or m.resolutionType is not null)
               and (:onlyUnresolved = false or m.resolutionType is null)
               and (:fromDate is null or m.businessDate >= :fromDate)
               and (:toDate is null or m.businessDate <= :toDate)
            """)
    Page<SettlementMismatchRow> search(@Param("checkType") MismatchCheckType checkType,
                                       @Param("onlyResolved") boolean onlyResolved,
                                       @Param("onlyUnresolved") boolean onlyUnresolved,
                                       @Param("fromDate") LocalDate fromDate,
                                       @Param("toDate") LocalDate toDate,
                                       Pageable pageable);

    /*
     * 아직 열린 건을 관리자의 과과금 정정으로 닫는다. 영향 행 수를 돌려준다.
     * 0 이면 그사이 다른 경로(대사 배치나 다른 조정)가 먼저 닫은 것이다.
     */
    @Modifying
    @Query("""
            update SettlementMismatch m
               set m.resolutionType = com.launchcatch.billing.entity.MismatchResolutionType.OVERCHARGE_REVERSAL,
                   m.resolvedAt = :now,
                   m.resolvedBy = :adminId,
                   m.updatedAt = :now
             where m.id = :id
               and m.resolutionType is null
            """)
    int resolveAsOverchargeReversal(@Param("id") Long id, @Param("adminId") Long adminId,
                                    @Param("now") LocalDateTime now);

    /*
     * 캠페인 소진 대사의 불일치 한 건을 기록한다. 같은 (check_type, target_id, business_date) 가 이미 있으면 그대로 둔다.
     * 같은 날을 다시 대조해도 행은 하나로 남는다(uk_settlement_mismatch_check_target_date).
     * INSERT IGNORE 는 쓰지 않는다. CHECK 위반까지 경고로 낮추고 행을 조용히 버린다(구현 계획 D-5).
     */
    @Modifying
    @Query(value = """
            INSERT INTO settlement_mismatch
                (check_type, business_date, target_id, expected_value, actual_value, job_execution_id,
                 created_at, updated_at)
            VALUES
                ('CAMPAIGN_SPEND_VS_LEDGER', :businessDate, :campaignId, :ledgerSpend, :counterSpend,
                 :jobExecutionId, :now, :now)
            ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id
            """, nativeQuery = true)
    int insertCampaignSpendMismatchIfAbsent(@Param("businessDate") LocalDate businessDate,
                                            @Param("campaignId") Long campaignId,
                                            @Param("ledgerSpend") long ledgerSpend,
                                            @Param("counterSpend") long counterSpend,
                                            @Param("jobExecutionId") Long jobExecutionId,
                                            @Param("now") LocalDateTime now);

    /*
     * 예약액 초과 소진 한 건을 기록한다. 기대값은 그날 예약액, 실제값은 원장 DEDUCT 합이다.
     * 같은 (check_type, target_id, business_date) 가 이미 있으면 그대로 둔다. 위와 같은 이유로 INSERT IGNORE 는 쓰지 않는다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO settlement_mismatch
                (check_type, business_date, target_id, expected_value, actual_value, job_execution_id,
                 created_at, updated_at)
            VALUES
                ('CAMPAIGN_OVERSPEND', :businessDate, :campaignId, :reserved, :ledgerSpend,
                 :jobExecutionId, :now, :now)
            ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id
            """, nativeQuery = true)
    int insertCampaignOverspendIfAbsent(@Param("businessDate") LocalDate businessDate,
                                        @Param("campaignId") Long campaignId,
                                        @Param("reserved") long reserved,
                                        @Param("ledgerSpend") long ledgerSpend,
                                        @Param("jobExecutionId") Long jobExecutionId,
                                        @Param("now") LocalDateTime now);

    /*
     * 당일 대조(구현 순서 11번)의 카운터 불일치 한 건을 CACHE_REBUILD 로 닫힌 채 기록한다. 배치가 카운터를
     * 원장 기준으로 이미 다시 만들었을 때 쓴다(D-3). 전일 대조의 insertCampaignSpendMismatchIfAbsent 는 미해결로
     * 남기므로 이 메서드와 다르다. 같은 (check_type, target_id, business_date) 가 이미 있으면 그대로 둔다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO settlement_mismatch
                (check_type, business_date, target_id, expected_value, actual_value, job_execution_id,
                 resolution_type, resolved_at, created_at, updated_at)
            VALUES
                ('CAMPAIGN_SPEND_VS_LEDGER', :businessDate, :campaignId, :ledgerSpend, :counterSpend,
                 :jobExecutionId, 'CACHE_REBUILD', :now, :now, :now)
            ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id
            """, nativeQuery = true)
    int insertCampaignSpendMismatchResolvedIfAbsent(@Param("businessDate") LocalDate businessDate,
                                                     @Param("campaignId") Long campaignId,
                                                     @Param("ledgerSpend") long ledgerSpend,
                                                     @Param("counterSpend") long counterSpend,
                                                     @Param("jobExecutionId") Long jobExecutionId,
                                                     @Param("now") LocalDateTime now);

    /*
     * PG_VS_CHARGE 대사(13번)의 미해결 불일치 한 건을 기록한다. 대상 ID 는 결제 ID 다.
     * 같은 (check_type, target_id, business_date) 가 이미 있으면 그대로 둔다. INSERT IGNORE 는
     * 쓰지 않는다. CHECK 위반까지 경고로 낮추고 행을 조용히 버린다(구현 계획 D-5).
     */
    @Modifying
    @Query(value = """
            INSERT INTO settlement_mismatch
                (check_type, business_date, target_id, expected_value, actual_value, job_execution_id,
                 created_at, updated_at)
            VALUES
                ('PG_VS_CHARGE', :businessDate, :paymentId, :expectedAmount, :actualAmount,
                 :jobExecutionId, :now, :now)
            ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id
            """, nativeQuery = true)
    int insertPgChargeMismatchIfAbsent(@Param("businessDate") LocalDate businessDate,
                                       @Param("paymentId") Long paymentId,
                                       @Param("expectedAmount") long expectedAmount,
                                       @Param("actualAmount") long actualAmount,
                                       @Param("jobExecutionId") Long jobExecutionId,
                                       @Param("now") LocalDateTime now);

    /*
     * PG_VS_CHARGE 대사가 빠진 CHARGE 를 보정한 뒤, 그 불일치를 LEDGER_CORRECTION 으로 닫힌 채
     * 기록한다. 해결자는 NULL 이다(배치가 자동으로 닫은 건, D-3). 같은 (check_type, target_id,
     * business_date) 가 이미 있으면 그대로 둔다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO settlement_mismatch
                (check_type, business_date, target_id, expected_value, actual_value, job_execution_id,
                 resolution_type, resolved_at, created_at, updated_at)
            VALUES
                ('PG_VS_CHARGE', :businessDate, :paymentId, :expectedAmount, :actualAmount,
                 :jobExecutionId, 'LEDGER_CORRECTION', :now, :now, :now)
            ON DUPLICATE KEY UPDATE settlement_mismatch_id = settlement_mismatch_id
            """, nativeQuery = true)
    int insertPgChargeMismatchResolvedIfAbsent(@Param("businessDate") LocalDate businessDate,
                                               @Param("paymentId") Long paymentId,
                                               @Param("expectedAmount") long expectedAmount,
                                               @Param("actualAmount") long actualAmount,
                                               @Param("jobExecutionId") Long jobExecutionId,
                                               @Param("now") LocalDateTime now);
}
