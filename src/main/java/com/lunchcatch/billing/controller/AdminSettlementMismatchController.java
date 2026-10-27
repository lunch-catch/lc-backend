package com.lunchcatch.billing.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.dto.SettlementMismatchItem;
import com.lunchcatch.billing.entity.MismatchCheckType;
import com.lunchcatch.billing.service.SettlementMismatchQueryService;
import com.lunchcatch.billing.service.SettlementMismatchResolutionService;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/settlement-mismatches")
@RequiredArgsConstructor
@Tag(name = "관리자 대사 불일치", description = "관리자가 정산 대사 배치가 찾은 불일치 건을 조회하고 조정 없이 닫는 API입니다.")
public class AdminSettlementMismatchController {

    private final SettlementMismatchQueryService queryService;
    private final SettlementMismatchResolutionService resolutionService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    @Operation(summary = "대사 불일치 조회",
            description = "대사 종류, 해결 여부, 대사 대상 영업일로 거릅니다. 전체 건수는 매출 현황의 불일치 건수로 씁니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 기간이 뒤집혔거나, 페이지 번호가 음수이거나, 허용하지 않는 정렬 값입니다.")
    })
    public ResponseEnvelope<PageResponse<SettlementMismatchItem>> search(
            @RequestParam(required = false) MismatchCheckType checkType,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(
                queryService.search(checkType, resolved, from, to, page, size, sortBy, sortDir));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{mismatchId}:closeWithoutAdjustment")
    @Operation(summary = "대사 불일치 조정 없이 종결",
            description = "원장을 바꾸지 않고 열린 대사 불일치를 NO_ADJUSTMENT_REQUIRED 로 닫습니다. 사유는 받지 않고 처리한 관리자와 시각을 남깁니다. "
                    + "CAMPAIGN_SPEND_VS_LEDGER 는 이 경로로만 닫습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "종결 성공"),
            @ApiResponse(responseCode = "404", description = "BILLING-020: 대사 불일치 건을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-021: 이미 해결된 대사 건입니다.")
    })
    public ResponseEnvelope<SettlementMismatchItem> closeWithoutAdjustment(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long mismatchId) {
        return ResponseEnvelope.success(resolutionService.closeWithoutAdjustment(admin.getId(), mismatchId));
    }
}
