package com.lunchcatch.billing.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/*
 * 내일 적용할 정책 저장 요청이다. 적용일은 받지 않고 서버가 내일로 정한다.
 * 상품마다 최소 충전 금액 이상인지는 두 값을 함께 봐야 해서 엔티티가 검증한다.
 */
public record PointPolicySaveRequest(
        @NotNull @Positive Long minChargeAmount,
        @NotEmpty List<@NotNull Long> chargeProducts,
        @Size(max = 255) String reason
) {
}
