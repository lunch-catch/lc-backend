package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.PointPolicy;
import java.time.LocalDate;
import java.util.List;

/*
 * 점주 충전 화면의 정책 응답이다.
 * 노출 단가와 하루 예산 하한은 운영 설정값이 소유하므로 여기 싣지 않는다.
 */
public record PointPolicyResponse(
        long minChargeAmount,
        List<Long> chargeProducts,
        LocalDate effectiveDate
) {

    public static PointPolicyResponse from(PointPolicy policy) {
        return new PointPolicyResponse(policy.getMinChargeAmount(), policy.getChargeProducts(),
                policy.getEffectiveDate());
    }
}
