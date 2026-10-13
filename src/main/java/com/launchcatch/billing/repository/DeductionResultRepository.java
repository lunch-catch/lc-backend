package com.launchcatch.billing.repository;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.entity.DeductionResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 노출 차감 판정 기록 저장소다
public interface DeductionResultRepository extends JpaRepository<DeductionResult, Long> {

    /*
     * 판정을 기록한다. 같은 serve_id 가 이미 있으면 그대로 둔다.
     *
     * 중복은 값을 바꾸지 않는 갱신(ON DUPLICATE KEY UPDATE serve_id = serve_id)으로 흡수한다. 예외가 나지 않으므로
     * 부른 트랜잭션이 롤백 표시가 되지 않는다. INSERT IGNORE 는 쓰지 않는다. 중복뿐 아니라 CHECK 와 NOT NULL
     * 위반까지 경고로 낮추고 행을 조용히 버린다. 선조회와 REQUIRES_NEW 도 쓰지 않는다(구현 계획 D-5).
     *
     * 영향받은 행 수는 새로 만들면 1, 이미 있으면 0 이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO deduction_result
                (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
            VALUES
                (:serveId, :campaignId, :ownerId, :businessDate, :billingStatus, :unitPrice, :now, :now)
            ON DUPLICATE KEY UPDATE serve_id = serve_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("serveId") byte[] serveId,
                       @Param("campaignId") Long campaignId,
                       @Param("ownerId") Long ownerId,
                       @Param("businessDate") LocalDate businessDate,
                       @Param("billingStatus") String billingStatus,
                       @Param("unitPrice") long unitPrice,
                       @Param("now") LocalDateTime now);

    // serve_id 를 BINARY(16) 바이트로 바꿔 넘기는 호출 편의 메서드다
    default int insertIfAbsent(UUID serveId, Long campaignId, Long ownerId, LocalDate businessDate,
                               DeductionStatus status, long unitPrice, LocalDateTime now) {
        return insertIfAbsent(ServeIds.toBytes(serveId), campaignId, ownerId, businessDate, status.name(), unitPrice, now);
    }
}
