package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
