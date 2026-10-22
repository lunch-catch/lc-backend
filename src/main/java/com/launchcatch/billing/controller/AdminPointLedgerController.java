package com.launchcatch.billing.controller;

import com.launchcatch.billing.dto.AdminLedgerItem;
import com.launchcatch.billing.dto.AdminPointSummaryResponse;
import com.launchcatch.billing.dto.SummaryPeriod;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.service.AdminPointLedgerService;
import com.launchcatch.billing.service.AdminPointSummaryService;
import com.launchcatch.global.response.CursorPageResponse;
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

/*
 * 점주 ID 를 필수 파라미터로 선언하지 않고 서비스에서 거절한다.
 * 스프링이 먼저 거절하면 COMMON-003 이 되어, 기간 누락과 같은 COMMON-002 로 답하지 못한다.
 */
@RestController
@RequestMapping("/v1/admin/point-ledger")
@RequiredArgsConstructor
@Tag(name = "관리자 포인트 원장", description = "관리자가 점주별 포인트 변동과 기간 합계를 조회하는 API입니다.")
public class AdminPointLedgerController {

    private final AdminPointLedgerService ledgerService;
    private final AdminPointSummaryService summaryService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    @Operation(
            summary = "점주별 포인트 변동 조회",
            description = "점주 한 명의 원장을 여섯 종류 모두 보여 주고 변동 전후 잔액을 누적으로 계산해 붙입니다. 기간은 최대 31일입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. nextPageToken 이 비어 있으면 마지막 페이지입니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 점주 ID나 기간이 없거나, 기간이 뒤집혔거나 31일을 넘었습니다. 정렬 방향이 올바르지 않습니다.")
    })
    public ResponseEnvelope<CursorPageResponse<AdminLedgerItem>> search(
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) EntryType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long campaignId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(
                ledgerService.search(ownerId, type, from, to, campaignId, pageToken, pageSize, sortDir));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/summary")
    @Operation(
            summary = "점주별 일·주·월 합계",
            description = "점주 한 명의 충전, 소진, 예약, 해제, 환불, 조정 합계를 구간별로 돌려줍니다. 일별 92일, 주별 53주, 월별 13개월까지입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. 변동이 없는 구간은 금액이 모두 0입니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 점주 ID나 단위, 기간이 없거나 뒤집혔거나 단위별 상한을 넘었습니다.")
    })
    public ResponseEnvelope<AdminPointSummaryResponse> summary(
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) SummaryPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(summaryService.summarize(ownerId, period, from, to, sortDir));
    }
}
