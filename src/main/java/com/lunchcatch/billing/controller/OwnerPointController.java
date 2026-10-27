package com.lunchcatch.billing.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.dto.OwnerLedgerItem;
import com.lunchcatch.billing.dto.PointBalanceResponse;
import com.lunchcatch.billing.dto.SpendGroupBy;
import com.lunchcatch.billing.dto.SpendSummaryResponse;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.service.OwnerPointBalanceService;
import com.lunchcatch.billing.service.OwnerPointLedgerService;
import com.lunchcatch.billing.service.OwnerSpendSummaryService;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/points")
@RequiredArgsConstructor
@Tag(name = "점주 포인트", description = "점주가 자신의 잔액과 포인트 변동을 조회하는 API입니다.")
public class OwnerPointController {

    private final OwnerPointBalanceService balanceService;
    private final OwnerPointLedgerService ledgerService;
    private final OwnerSpendSummaryService spendSummaryService;

    @GetMapping("/balance")
    @Operation(
            summary = "잔액 조회",
            description = "원장 합계로 계산한 잔액과 오늘 예약 중인 포인트를 돌려줍니다. 캐시된 값으로 대신하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "503", description = "BILLING-006: 원장을 읽지 못했습니다.")
    })
    public ResponseEnvelope<PointBalanceResponse> balance(@AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(balanceService.getBalance(owner.getId()));
    }

    @GetMapping("/ledger")
    @Operation(
            summary = "포인트 변동 내역",
            description = "충전, 예약, 예약 해제, 환불, 조정 내역을 최근 순으로 돌려줍니다. 노출마다 쌓이는 차감 내역은 내리지 않고 소진 집계로 봅니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. nextPageToken 이 비어 있으면 마지막 페이지입니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 기간이 뒤집혔거나 DEDUCT 를 종류로 지정했습니다.")
    })
    public ResponseEnvelope<CursorPageResponse<OwnerLedgerItem>> ledger(
            @AuthenticationPrincipal CustomUserDetails owner,
            @RequestParam(required = false) List<EntryType> type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long campaignId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEnvelope.success(
                ledgerService.list(owner.getId(), type, from, to, campaignId, pageToken, pageSize));
    }

    @GetMapping("/spend-summary")
    @Operation(
            summary = "캠페인·일별 소진 집계",
            description = "원장에서 직접 계산하므로 기간은 최대 31일입니다. groupBy 는 DAY, CAMPAIGN, DAY_CAMPAIGN 입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 기간이 없거나 뒤집혔거나 31일을 넘었습니다.")
    })
    public ResponseEnvelope<SpendSummaryResponse> spendSummary(
            @AuthenticationPrincipal CustomUserDetails owner,
            @RequestParam(defaultValue = "DAY") SpendGroupBy groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long campaignId) {
        return ResponseEnvelope.success(
                spendSummaryService.summarize(owner.getId(), groupBy, from, to, campaignId));
    }
}
