package com.launchcatch.billing.repository;

import java.util.List;
import java.util.Optional;

// 조건이 동적으로 붙고 떨어지는 결제 조회다
public interface PaymentRepositoryCustom {

    // 관리자 결제 목록의 한 페이지다. 재조회 아웃박스가 있으면 함께 읽는다
    List<AdminPaymentRow> findAdminPayments(AdminPaymentCondition condition);

    // 결제 한 건이다. 목록과 같은 모양이라 재조회 응답도 이것을 쓴다
    Optional<AdminPaymentRow> findAdminPaymentById(Long paymentId);

    // 같은 조건의 전체 건수다. 정렬과 페이지 조건은 쓰지 않는다
    long countAdminPayments(AdminPaymentCondition condition);
}
