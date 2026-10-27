package com.lunchcatch.billing.dto;

/*
 * 정산 안에서만 쓰는 통보다. 결제 승인으로 충전(CHARGE) 행이 새로 생겼다는 뜻이다.
 * 충전 직후 재예약이 커밋 뒤(AFTER_COMMIT)에 받는다. 다른 도메인에 공개하지 않으므로 이름에 Event 를 붙이지 않는다.
 */
public record PointCharged(
        Long ownerId,
        Long paymentId,
        long amount
) {
}
