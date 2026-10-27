package com.lunchcatch.billing.repository;

import com.lunchcatch.billing.entity.PaymentStatus;
import java.time.LocalDateTime;

/*
 * 관리자 결제 목록의 질의 조건이다. null 인 조건은 걸지 않는다.
 * 요청 시각(created_at)은 [fromInclusive, toExclusive) 범위이고, 검색어는 모두 정확 일치다.
 * 같은 값끼리는 결제 ID 를 같은 방향으로 보조 정렬한다.
 */
public record AdminPaymentCondition(
        Long ownerId,
        String orderId,
        String pgTid,
        PaymentStatus status,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        Sort sort,
        boolean ascending,
        long offset,
        int limit
) {

    public enum Sort {
        REQUESTED_AT,
        AMOUNT,
        /** 승인 전 결제(NULL)는 방향과 관계없이 맨 뒤에 둔다. */
        APPROVED_AT
    }
}
