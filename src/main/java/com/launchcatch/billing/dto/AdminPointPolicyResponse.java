package com.launchcatch.billing.dto;

// 오늘 적용 중인 정책과 내일 적용할 정책이며 내일 적용분이 아직 없으면 next 는 null 이다
public record AdminPointPolicyResponse(
        AdminPointPolicyItem current,
        AdminPointPolicyItem next
) {
}
