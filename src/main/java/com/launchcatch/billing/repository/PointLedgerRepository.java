package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointLedger;
import org.springframework.data.jpa.repository.JpaRepository;

// 원장 저장소이며 잔액 합계와 기간 조회는 그것을 쓰는 작업과 함께 더한다
public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {
}
