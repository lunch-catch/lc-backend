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
}
