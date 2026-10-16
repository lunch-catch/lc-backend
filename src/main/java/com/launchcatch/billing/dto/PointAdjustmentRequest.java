package com.launchcatch.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * 포인트 수동 조정 요청이다.
 *
 * requestId 는 관리자 화면이 조정 폼을 열 때 만든 멱등 키다. 레포 공통 규약이 별도 헤더를 두지 않아 본문으로 받는다.
 * amount 가 0 이 아닌지, mismatchId 가 있을 때 양수인지는 다른 필드와 함께 봐야 해서 서비스가 검사한다.
 */
public record PointAdjustmentRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long ownerId,
        @NotNull Long amount,
        @NotBlank @Size(max = 255) String reason,
        Long campaignId,
        Long mismatchId
) {
}
