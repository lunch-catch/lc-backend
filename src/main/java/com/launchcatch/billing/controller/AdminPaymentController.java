package com.launchcatch.billing.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.dto.AdminPaymentItem;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.service.AdminPaymentQueryService;
import com.launchcatch.billing.service.AdminPaymentRecheckService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/payments")
@RequiredArgsConstructor
@Tag(name = "관리자 결제", description = "관리자가 점주의 결제(충전) 내역을 조회하고 확인 중인 결제를 재조회하는 API입니다.")
public class AdminPaymentController {

    private final AdminPaymentQueryService queryService;
    private final AdminPaymentRecheckService recheckService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    @Operation(
            summary = "결제 내역 조회",
            description = "요청 시각 기준 기간과 상태로 거르고 점주 ID, 주문 번호, 결제 식별자를 정확 일치로 검색합니다. "
                    + "확인 중(UNKNOWN)은 그대로 보여 주고, 재조회 아웃박스가 있으면 함께 내립니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 기간이 뒤집혔거나, 검색어만 있고 검색 종류가 없거나, 점주 ID가 숫자가 아니거나, 허용하지 않는 정렬 값입니다.")
    })
    public ResponseEnvelope<PageResponse<AdminPaymentItem>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String searchType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(
                queryService.search(from, to, status, searchType, keyword, page, size, sortBy, sortDir));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{paymentId}:recheck")
    @Operation(
            summary = "확인 중인 결제 재조회",
            description = "결제대행사를 한 번 조회해 결과로 결제를 확정합니다. 확인 중(UNKNOWN)인 결제만 됩니다. "
                    + "조회가 분명한 답을 주지 않으면 결제는 그대로 두고 사유만 남깁니다. 처리한 관리자는 감사 로그에 남습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재조회 성공. 갱신된 결제를 돌려줍니다."),
            @ApiResponse(responseCode = "404", description = "BILLING-004: 결제를 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-005: 확인 중인 결제가 아니어서 재조회할 수 없습니다.")
    })
    public ResponseEnvelope<AdminPaymentItem> recheck(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable Long paymentId) {
        return ResponseEnvelope.success(recheckService.recheck(admin.getId(), paymentId));
    }
}
