package com.lunchcatch.billing.dto;

import com.lunchcatch.billing.entity.PointPolicy;
import java.time.LocalDate;
import java.util.List;

/*
 * 관리자 화면의 정책 한 행이다. 현재 적용분, 내일 적용분, 이력의 항목이 모두 이 모양이다.
 * 저장한 관리자가 없는 최초 정책 행은 createdBy 가 null 이다.
 */
public record AdminPointPolicyItem(
        long minChargeAmount,
        List<Long> chargeProducts,
        LocalDate effectiveDate,
        String changeReason,
        Long createdBy
) {

    public static AdminPointPolicyItem from(PointPolicy policy) {
        return new AdminPointPolicyItem(policy.getMinChargeAmount(), policy.getChargeProducts(),
                policy.getEffectiveDate(), policy.getChangeReason(), policy.getCreatedBy());
    }
}
