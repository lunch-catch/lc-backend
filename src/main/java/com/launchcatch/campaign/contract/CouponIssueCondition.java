package com.launchcatch.campaign.contract;

import java.time.LocalTime;

/*
 * 쿠폰이 발급 자격을 판단할 때 캠페인에서 읽어 가는 값이다 (설계 문서 2.2 절의 coupon -> campaign).
 * 엔티티를 내보내지 않고 이 record 만 넘긴다.
 */
public record CouponIssueCondition(
        Long campaignId,
        int issueQuantity,
        LocalTime usableStartTime,
        LocalTime usableEndTime
) {
}
