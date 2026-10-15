package com.launchcatch.billing.dto;

// 조정 결과와 이번 요청이 새로 기록했는지이며 컨트롤러가 201 과 200 을 가른다
public record PointAdjustmentResult(
        PointAdjustmentResponse adjustment,
        boolean created
) {
}
