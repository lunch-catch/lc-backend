package com.launchcatch.campaign.contract;

import java.util.Optional;

// 쿠폰 도메인이 발급 조건을 읽는 계약이다
public interface CouponIssueConditionQueryService {

    Optional<CouponIssueCondition> findActiveCondition(Long campaignId);
}
