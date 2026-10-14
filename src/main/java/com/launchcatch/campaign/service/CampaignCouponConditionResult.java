package com.launchcatch.campaign.service;

import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;

// 새로 만들었는지 여부를 컨트롤러에 전달해 201과 200을 가른다(campaign-api-spec.md).
public record CampaignCouponConditionResult(CampaignCouponConditionResponse response, boolean created) {
}
