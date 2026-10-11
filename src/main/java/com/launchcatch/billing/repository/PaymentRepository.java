package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * 결제 저장소다. 이 판은 매핑만 세우고 조회 메서드는 두지 않는다.
 * 주문번호 조회와 점주별 목록은 그것을 쓰는 작업과 함께 더한다. 쓰는 자리 없이 미리 적으면
 * 어느 인덱스를 타는지 확인할 방법이 없다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
