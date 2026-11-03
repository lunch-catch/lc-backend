package com.lunchcatch.billing.entity;

// 재예약 아웃박스의 상태이며 recharge_reservation_outbox.status 의 CHECK 가 같은 세 값을 묶는다
public enum RechargeReservationStatus {

    /** 재예약을 기다린다. next_retry_at 이 필수다. */
    PENDING,

    /** 재예약을 마쳤다. 다시 예약할 캠페인이 없었던 경우도 여기다. */
    DONE,

    /** 충전한 영업일이 지나 재예약하지 않고 닫았다. 00:00 예약이 그 잔액을 배분한다. */
    EXPIRED
}
