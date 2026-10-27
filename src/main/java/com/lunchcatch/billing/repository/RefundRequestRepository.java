package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.RefundRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 환불 요청 저장소이며 점주별 목록은 idx_refund_request_owner_created, 관리자 상태 목록은 idx_refund_request_status_created 를 탄다
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    // 그 점주에게 이 상태의 요청이 하나라도 있는지 본다
    boolean existsByOwnerIdAndStatus(Long ownerId, RefundStatus status);

    // 요청의 점주 ID 만 읽으며 점주 ID 는 바뀌지 않아 잠그기 전에 읽어도 된다
    @Query("select r.ownerId from RefundRequest r where r.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    /*
     * 요청 행을 잠그고 읽는다. 승인과 반려는 이 잠금 아래에서 상태를 보고 바꾼다.
     * 둘이 동시에 와도 뒤에 온 쪽이 앞의 결과를 보고 BILLING-015 로 멈춘다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequest r where r.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") Long id);

    // 점주 목록의 첫 페이지이며 요청 시각 내림차순, 같은 시각은 ID 내림차순이다
    @Query("""
            select r from RefundRequest r
             where r.ownerId = :ownerId
             order by r.createdAt desc, r.id desc
            """)
    List<RefundRequest> findOwnerHistory(@Param("ownerId") Long ownerId, Pageable limit);

    // 커서 다음 페이지이며 조건과 정렬은 첫 페이지와 같다
    @Query("""
            select r from RefundRequest r
             where r.ownerId = :ownerId
               and (r.createdAt < :cursorCreatedAt
                    or (r.createdAt = :cursorCreatedAt and r.id < :cursorId))
             order by r.createdAt desc, r.id desc
            """)
    List<RefundRequest> findOwnerHistoryAfter(@Param("ownerId") Long ownerId,
                                              @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                              @Param("cursorId") Long cursorId,
                                              Pageable limit);

    // 관리자 목록이며 비어 있는 조건은 걸지 않고 정렬은 호출하는 쪽이 Pageable 에 담는다
    @Query("""
            select r from RefundRequest r
             where (:status is null or r.status = :status)
               and (:ownerId is null or r.ownerId = :ownerId)
               and (:fromInclusive is null or r.createdAt >= :fromInclusive)
               and (:toExclusive is null or r.createdAt < :toExclusive)
            """)
    Page<RefundRequest> search(@Param("status") RefundStatus status,
                               @Param("ownerId") Long ownerId,
                               @Param("fromInclusive") LocalDateTime fromInclusive,
                               @Param("toExclusive") LocalDateTime toExclusive,
                               Pageable pageable);
}
