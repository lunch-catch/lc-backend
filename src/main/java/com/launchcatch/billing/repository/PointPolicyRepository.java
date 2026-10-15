package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointPolicy;
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
 * 포인트 정책 저장소다.
 * 이력형 테이블이라 지금 적용 중인 정책은 적용일이 오늘 이하인 행 중 가장 늦은 행이고, effective_date UNIQUE 인덱스를 탄다.
 */
public interface PointPolicyRepository extends JpaRepository<PointPolicy, Long> {

    // 적용일이 그 날짜 이하인 정책 중 가장 최근 것을 찾는다
    Optional<PointPolicy> findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate date);

    // 관리자 화면이 내일 적용분을 찾을 때 쓰는, 그 날짜에 적용되는 정책 행이다
    Optional<PointPolicy> findByEffectiveDate(LocalDate effectiveDate);

    // 정책 이력이며 적용일 내림차순이다
    Page<PointPolicy> findAllByOrderByEffectiveDateDesc(Pageable pageable);

    /*
     * 그 날짜의 정책 행을 넣고, 이미 있으면 값을 덮어쓴다. 같은 날 다시 저장하면 마지막 값이 남는다.
     *
     * 지우고 넣으면 두 관리자가 동시에 처음 저장할 때 둘 다 행이 없다고 보고 넣다가 UNIQUE 위반이 난다.
     * 그래서 엔티티를 거치지 않고 한 문장으로 끝낸다(구현 계획 G-7). 생성 시각은 처음 넣은 값을 그대로 둔다.
     * 문장이 끝나면 영속성 컨텍스트를 비워, 같은 트랜잭션에서 다시 읽을 때 덮어쓴 값을 보게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO point_policy
                (min_charge_amount, charge_products, change_reason, effective_date, created_by, created_at, updated_at)
            VALUES (:minChargeAmount, CAST(:chargeProducts AS JSON), :changeReason, :effectiveDate, :createdBy, :now, :now)
                AS new_policy
            ON DUPLICATE KEY UPDATE
                min_charge_amount = new_policy.min_charge_amount,
                charge_products = new_policy.charge_products,
                change_reason = new_policy.change_reason,
                created_by = new_policy.created_by,
                updated_at = new_policy.updated_at
            """, nativeQuery = true)
    int upsert(@Param("effectiveDate") LocalDate effectiveDate,
               @Param("minChargeAmount") long minChargeAmount,
               @Param("chargeProducts") String chargeProductsJson,
               @Param("changeReason") String changeReason,
               @Param("createdBy") Long createdBy,
               @Param("now") LocalDateTime now);
}
