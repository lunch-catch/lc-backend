package com.lunchcatch.billing.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.dto.PointAdjustmentRequest;
import com.lunchcatch.billing.dto.PointAdjustmentResponse;
import com.lunchcatch.billing.dto.PointAdjustmentResult;
import com.lunchcatch.billing.service.PointAdjustmentService;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/point-adjustments")
@RequiredArgsConstructor
@Tag(name = "관리자 포인트 조정", description = "관리자가 사후에 확인된 과오 차감을 정정하는 포인트 수동 조정 API입니다.")
public class AdminPointAdjustmentController {

    private final PointAdjustmentService adjustmentService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    @Operation(summary = "포인트 수동 조정",
            description = "점주 원장에 조정(ADJUST)을 기록합니다. requestId 가 같고 내용이 같은 요청은 처음 결과를 200 으로 돌려줍니다. "
                    + "campaignId 를 주면 정정이 가리키는 원래 영업일 targetBusinessDate 도 줘야 하고, 캠페인 없는 조정은 줄 수 없습니다. "
                    + "mismatchId 를 주면 그 대사 불일치 건을 MANUAL_ADJUSTMENT 로 닫습니다. PG_VS_CHARGE 건은 PG 승인 금액에서 그 결제의 CHARGE 를 뺀 금액이어야 하고 campaignId 를 줄 수 없습니다. "
                    + "CAMPAIGN_OVERSPEND 건은 초과액 이하의 양수여야 하고 targetBusinessDate 가 그 건의 영업일과 같아야 합니다. CAMPAIGN_SPEND_VS_LEDGER 건은 조정으로 닫을 수 없습니다. "
                    + "처리한 관리자는 감사 로그에 남습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "조정을 새로 기록했습니다."),
            @ApiResponse(responseCode = "200", description = "같은 요청을 이미 처리해 처음 결과를 돌려줍니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 필수값이 없거나, 금액이 0이거나, 대사 건을 닫을 수 없는 금액이거나, 캠페인 대사 건인데 campaignId 가 대상과 다르거나, "
                    + "결제 대사 건인데 campaignId 가 있거나, 카운터 대 원장 대사 건이거나, campaignId 와 targetBusinessDate 의 짝이 맞지 않거나, "
                    + "targetBusinessDate 가 오늘 이후이거나, 캠페인 대사 건의 영업일과 다릅니다."),
            @ApiResponse(responseCode = "404", description = "BILLING-019: 점주를 찾을 수 없습니다. BILLING-020: 대사 불일치 건을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-016: 조정 후 잔액이 음수가 됩니다. BILLING-018: 같은 키로 내용이 다른 요청입니다. "
                    + "BILLING-021: 이미 해결된 대사 건입니다. BILLING-022: 대사 건이 이 점주의 것이 아닙니다."),
            @ApiResponse(responseCode = "503", description = "BILLING-006: 잠시 후 같은 requestId 로 다시 시도해 주세요.")
    })
    public ResponseEntity<ResponseEnvelope<PointAdjustmentResponse>> adjust(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody PointAdjustmentRequest request) {
        PointAdjustmentResult result = adjustmentService.adjust(admin.getId(), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ResponseEnvelope.success(result.adjustment()));
    }
}
