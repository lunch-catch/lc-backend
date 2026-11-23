package com.lunchcatch.billing.deduction.repository;

import com.lunchcatch.billing.contract.DeductionStatus;
import com.lunchcatch.billing.deduction.dto.ServeIds;
import com.lunchcatch.billing.deduction.entity.DeductionResult;
import com.lunchcatch.billing.dto.CampaignSpend;
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

// 노출 차감 판정 기록 저장소다
public interface DeductionResultRepository extends JpaRepository<DeductionResult, Long> {

    /*
     * 판정을 기록한다. 같은 serve_id 가 이미 있으면 판정과 금액은 그대로 두고 updated_at 만 바꾼다.
     *
     * 중복은 ON DUPLICATE KEY UPDATE 로 흡수한다. 예외가 나지 않으므로 부른 트랜잭션이 롤백 표시가 되지 않는다.
     * INSERT IGNORE 는 쓰지 않는다. 중복뿐 아니라 CHECK 와 NOT NULL 위반까지 경고로 낮추고 행을 조용히 버린다.
     * 선조회와 REQUIRES_NEW 도 쓰지 않는다.
     *
     * 중복 분기가 updated_at 을 바꾸는 이유: 값을 바꾸지 않는 갱신(serve_id = serve_id)은 Connector/J 의 기본 설정
     * (CLIENT_FOUND_ROWS)에서 새로 만든 경우와 같은 1 을 돌려줘 둘을 구분할 수 없다. 바뀐 행은 2 를 돌려준다.
     * 같은 마이크로초에 다시 부르거나 시계가 고정돼 있어도 중복 갱신이 생기도록 기존 시각보다 최소 1마이크로초 늘린다.
     *
     * 영향받은 행 수는 새로 만들면 1, 이미 있으면 2 다. 호출하는 쪽은 1 이 아니면 중복으로 본다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO deduction_result
                (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
            VALUES
                (:serveId, :campaignId, :ownerId, :businessDate, :billingStatus, :unitPrice, :now, :now)
            ON DUPLICATE KEY UPDATE updated_at = GREATEST(:now, updated_at + INTERVAL 1 MICROSECOND)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("serveId") byte[] serveId,
                       @Param("campaignId") Long campaignId,
                       @Param("ownerId") Long ownerId,
                       @Param("businessDate") LocalDate businessDate,
                       @Param("billingStatus") String billingStatus,
                       @Param("unitPrice") long unitPrice,
                       @Param("now") LocalDateTime now);

    /*
     * 처음 기록된 판정의 이름이다. 없으면 null 이다.
     *
     * 잠금 읽기(FOR SHARE)로 읽는다. 같은 serve_id 의 INSERT 가 다른 트랜잭션의 커밋을 기다렸다가 중복으로 끝난 경우,
     * 일반 SELECT 는 이 트랜잭션이 먼저 잡은 스냅샷을 읽어 방금 커밋된 행을 보지 못한다.
     */
    @Query(value = "SELECT billing_status FROM deduction_result WHERE serve_id = :serveId FOR SHARE", nativeQuery = true)
    String findStatusNameForShare(@Param("serveId") byte[] serveId);

    // 처음 기록된 판정이다. 중복 호출이 첫 판정을 그대로 돌려줄 때 쓴다
    default Optional<DeductionStatus> findFirstStatus(UUID serveId) {
        return Optional.ofNullable(findStatusNameForShare(ServeIds.toBytes(serveId))).map(DeductionStatus::valueOf);
    }

    // 영업일의 캠페인과 과금 상태별 판정 건수다
    @Query("""
            select new com.lunchcatch.billing.deduction.repository.DeductionCountRow(d.campaignId, d.billingStatus, count(d))
              from DeductionResult d
             where d.businessDate = :businessDate
             group by d.campaignId, d.billingStatus
            """)
    List<DeductionCountRow> countByStatusOn(@Param("businessDate") LocalDate businessDate);

    /*
     * 캠페인이 그 서빙 영업일에 쓴 금액이다. BILLED 판정의 단가 합이고 행이 없으면 0 이다.
     * 소진의 정본이다(#1287). 해시 재구성이 Valkey 의 spend 를 다시 채우고, 재예약이 재개를 알릴 때 쓴다.
     */
    @Query("""
            select coalesce(sum(d.unitPrice), 0L)
              from DeductionResult d
             where d.campaignId = :campaignId
               and d.businessDate = :businessDate
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
            """)
    long sumBilledForCampaignOn(@Param("campaignId") Long campaignId, @Param("businessDate") LocalDate businessDate);

    // 위와 같은 합을 캠페인 여러 개에 대해 한 번에 구한다. 그날 BILLED 가 없는 캠페인은 결과에 없다(소진 0)
    @Query("""
            select new com.lunchcatch.billing.dto.CampaignSpend(d.campaignId, sum(d.unitPrice))
              from DeductionResult d
             where d.campaignId in :campaignIds
               and d.businessDate = :businessDate
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
             group by d.campaignId
            """)
    List<CampaignSpend> sumBilledForCampaignsOn(@Param("campaignIds") Collection<Long> campaignIds,
                                                @Param("businessDate") LocalDate businessDate);

    // 집계 조회가 쓰는 그 서빙 영업일의 점주와 캠페인별 BILLED 단가 합이다
    @Query("""
            select new com.lunchcatch.billing.deduction.repository.OwnerCampaignBilledRow(
                       d.ownerId, d.campaignId, sum(d.unitPrice))
              from DeductionResult d
             where d.businessDate = :businessDate
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
             group by d.ownerId, d.campaignId
            """)
    List<OwnerCampaignBilledRow> sumBilledOn(@Param("businessDate") LocalDate businessDate);

    // 점주의 모든 BILLED 단가 합이다. 예약 중 포인트(예약 - 해제 - 소진)의 소진 몫이다
    @Query("""
            select coalesce(sum(d.unitPrice), 0L)
              from DeductionResult d
             where d.ownerId = :ownerId
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
            """)
    long sumBilledForOwner(@Param("ownerId") Long ownerId);

    // 기록 시각이 before 이전인 BILLED 단가 합이다. 미소진 포인트의 소진 몫이라 영업일이 아니라 기록 시각으로 센다
    @Query("""
            select coalesce(sum(d.unitPrice), 0L)
              from DeductionResult d
             where d.createdAt < :before
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
            """)
    long sumBilledRecordedBefore(@Param("before") LocalDateTime before);

    // 기록 시각이 [from, to) 인 BILLED 단가 합이다
    @Query("""
            select coalesce(sum(d.unitPrice), 0L)
              from DeductionResult d
             where d.createdAt >= :from
               and d.createdAt < :to
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
            """)
    long sumBilledRecordedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 점주의 기록 시각이 [from, to) 인 캠페인별 BILLED 단가 합과 건수다. campaignId 가 null 이면 캠페인을 거르지 않는다
    @Query("""
            select new com.lunchcatch.billing.deduction.repository.CampaignBilledRow(
                       d.campaignId, sum(d.unitPrice), count(d))
              from DeductionResult d
             where d.ownerId = :ownerId
               and d.createdAt >= :from
               and d.createdAt < :to
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
               and (:campaignId is null or d.campaignId = :campaignId)
             group by d.campaignId
            """)
    List<CampaignBilledRow> sumBilledByCampaignRecordedBetween(@Param("ownerId") Long ownerId,
                                                               @Param("from") LocalDateTime from,
                                                               @Param("to") LocalDateTime to,
                                                               @Param("campaignId") Long campaignId);

    // 점주의 서빙 영업일이 [from, to] 인 영업일별 BILLED 단가 합이다. 자정 뒤에 기록된 전날 노출도 전날에 들어간다
    @Query("""
            select new com.lunchcatch.billing.deduction.repository.BusinessDateBilledRow(
                       d.businessDate, sum(d.unitPrice))
              from DeductionResult d
             where d.ownerId = :ownerId
               and d.businessDate between :from and :to
               and d.billingStatus = com.lunchcatch.billing.contract.DeductionStatus.BILLED
             group by d.businessDate
            """)
    List<BusinessDateBilledRow> sumBilledByBusinessDate(@Param("ownerId") Long ownerId,
                                                        @Param("from") LocalDate from,
                                                        @Param("to") LocalDate to);

    // serve_id 를 BINARY(16) 바이트로 바꿔 넘기는 호출 편의 메서드다
    default int insertIfAbsent(UUID serveId, Long campaignId, Long ownerId, LocalDate businessDate,
                               DeductionStatus status, long unitPrice, LocalDateTime now) {
        return insertIfAbsent(ServeIds.toBytes(serveId), campaignId, ownerId, businessDate, status.name(), unitPrice, now);
    }
}
