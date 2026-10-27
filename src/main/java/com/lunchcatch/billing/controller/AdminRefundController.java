package com.lunchcatch.billing.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.dto.AdminRefundRequestItem;
import com.lunchcatch.billing.dto.RefundProcessResponse;
import com.lunchcatch.billing.dto.RefundRejectRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.billing.service.AdminRefundProcessService;
import com.lunchcatch.billing.service.AdminRefundService;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/refund-requests")
@RequiredArgsConstructor
@Tag(name = "관리자 환불", description = "관리자가 점주의 환불 요청을 조회하고 승인하거나 반려하는 API입니다.")
public class AdminRefundController {

    private final AdminRefundService refundService;
    private final AdminRefundProcessService processService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    @Operation(summary = "환불 요청 목록",
            description = "상태와 점주로 정확 일치로 거르고 요청 시각 기준 기간으로 거릅니다. 점주의 현재 잔액과 집행 중 캠페인 여부를 함께 내립니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 기간이 뒤집혔거나, 페이지 번호가 음수이거나, 허용하지 않는 정렬 값입니다.")
    })
    public ResponseEnvelope<PageResponse<AdminRefundRequestItem>> search(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(
                refundService.search(status, ownerId, from, to, page, size, sortBy, sortDir));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{refundRequestId}:approve")
    @Operation(summary = "환불 승인",
            description = "점주 잔액에서 요청 금액만큼 환불을 기록하고 요청을 완료로 닫습니다. 실제 송금은 하지 않는 mock 처리입니다. 처리한 관리자는 감사 로그에 남습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인 성공"),
            @ApiResponse(responseCode = "404", description = "BILLING-014: 환불 요청을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-015: 이미 처리된 요청입니다. BILLING-010: 집행 중인 캠페인이 있습니다. BILLING-013: 잔액이 요청 금액보다 적습니다."),
            @ApiResponse(responseCode = "503", description = "BILLING-006: 잠시 후 다시 시도해 주세요.")
    })
    public ResponseEnvelope<RefundProcessResponse> approve(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long refundRequestId) {
        return ResponseEnvelope.success(processService.approve(admin.getId(), refundRequestId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{refundRequestId}:reject")
    @Operation(summary = "환불 반려",
            description = "요청을 반려하고 사유를 남깁니다. 원장은 바뀌지 않습니다. 사유는 점주에게 보입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "반려 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 반려 사유가 없거나 255자를 넘습니다."),
            @ApiResponse(responseCode = "404", description = "BILLING-014: 환불 요청을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-015: 이미 처리된 요청입니다.")
    })
    public ResponseEnvelope<RefundProcessResponse> reject(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long refundRequestId,
            @Valid @RequestBody RefundRejectRequest request) {
        return ResponseEnvelope.success(
                processService.reject(admin.getId(), refundRequestId, request.rejectReason()));
    }
}
