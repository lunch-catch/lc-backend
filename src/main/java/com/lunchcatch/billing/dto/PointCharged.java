package com.lunchcatch.billing.dto;

/*
 * 정산 안에서만 쓰는 값이다. 결제 승인으로 충전(CHARGE) 행이 새로 생겼다는 뜻이다.
 * 재예약 워커가 재예약 대기 행과 그 결제로 만들어 충전 직후 재예약에 넘긴다. 다른 도메인에 공개하지 않는다.
 */
public record PointCharged(
        Long ownerId,
        Long paymentId,
        long amount
) {
}
