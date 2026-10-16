package com.launchcatch.billing.dto;

import java.time.LocalDate;

/*
 * 정산 안에서만 쓰는 통보다. 충전 직후 재예약이 그날 계획의 예약액을 늘렸다는 뜻이다.
 * 커밋 뒤에 당일 소진 해시의 reserved 를 맞추려고 둔다. 다른 도메인에 공개하지 않으므로 이름에 Event 를 붙이지 않는다.
 */
public record RechargeReserveIncreased(
        Long campaignId,
        LocalDate businessDate,
        long reservedAmount
) {
}
