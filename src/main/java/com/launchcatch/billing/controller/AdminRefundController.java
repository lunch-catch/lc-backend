package com.launchcatch.billing.controller;

import com.launchcatch.billing.dto.AdminRefundRequestItem;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.service.AdminRefundService;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/refund-requests")
@RequiredArgsConstructor
@Tag(name = "관리자 환불", description = "관리자가 점주의 환불 요청을 조회하고 승인하거나 반려하는 API입니다.")
public class AdminRefundController {

    private final AdminRefundService refundService;

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
}
