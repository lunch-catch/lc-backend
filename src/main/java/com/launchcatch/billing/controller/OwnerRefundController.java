package com.launchcatch.billing.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.dto.OwnerRefundRequestItem;
import com.launchcatch.billing.dto.RefundPreviewResponse;
import com.launchcatch.billing.dto.RefundRequestCreateRequest;
import com.launchcatch.billing.dto.RefundRequestCreateResponse;
import com.launchcatch.billing.service.OwnerRefundService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/refund-requests")
@RequiredArgsConstructor
@Tag(name = "점주 환불", description = "점주가 잔액 전액 환불을 미리 보고 요청하는 API입니다.")
public class OwnerRefundController {

    private final OwnerRefundService refundService;

    @GetMapping("/preview")
    @Operation(summary = "환불 미리보기",
            description = "환불 가능 잔액과 예상 환불액, 지금 요청할 수 있는지를 보여 줍니다. 아무것도 기록하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    public ResponseEnvelope<RefundPreviewResponse> preview(@AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(refundService.preview(owner.getId()));
    }

    @PostMapping
    @Operation(summary = "환불 요청",
            description = "요청 시점 잔액 전액을 환불해 달라고 요청합니다. 금액은 받지 않습니다. 관리자가 승인해야 원장에 반영됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "요청 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 사유가 255자를 넘습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-010: 집행 중인 캠페인이 있습니다. BILLING-011: 처리 대기 중인 요청이 이미 있습니다."),
            @ApiResponse(responseCode = "422", description = "BILLING-012: 환불할 수 있는 잔액이 없습니다."),
            @ApiResponse(responseCode = "503", description = "BILLING-006: 잠시 후 다시 시도해 주세요.")
    })
    public ResponseEntity<ResponseEnvelope<RefundRequestCreateResponse>> request(
            @AuthenticationPrincipal CustomUserDetails owner,
            @Valid @RequestBody(required = false) RefundRequestCreateRequest request) {
        String reason = request == null ? null : request.reason();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(refundService.request(owner.getId(), reason)));
    }

    @GetMapping
    @Operation(summary = "내 환불 요청 목록",
            description = "요청 시각 내림차순 커서 목록입니다. 다음 페이지는 응답의 nextPageToken 을 그대로 보냅니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. nextPageToken 이 null 이면 마지막 페이지입니다.")
    })
    public ResponseEnvelope<CursorPageResponse<OwnerRefundRequestItem>> list(
            @AuthenticationPrincipal CustomUserDetails owner,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEnvelope.success(refundService.list(owner.getId(), pageToken, pageSize));
    }
}
