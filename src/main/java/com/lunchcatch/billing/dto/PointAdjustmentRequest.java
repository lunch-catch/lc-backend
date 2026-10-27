package com.lunchcatch.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/*
 * 포인트 수동 조정 요청이다.
 *
 * requestId 는 관리자 화면이 조정 폼을 열 때 만든 멱등 키다. 레포 공통 규약이 별도 헤더를 두지 않아 본문으로 받는다.
 * targetBusinessDate 는 정정이 가리키는 원래 영업일이다. campaignId 가 있으면 필수이고 없으면 보낼 수 없다.
 * amount 가 0 이 아닌지, mismatchId 가 있을 때 그 건을 닫을 수 있는 금액인지, targetBusinessDate 가 campaignId 와 짝이 맞고
 * 미래가 아닌지는 다른 필드와 함께 봐야 해서 서비스가 검사한다.
 */
public record PointAdjustmentRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long ownerId,
        @NotNull Long amount,
        @NotBlank @Size(max = 255) String reason,
        Long campaignId,
        LocalDate targetBusinessDate,
        Long mismatchId
) {
}
