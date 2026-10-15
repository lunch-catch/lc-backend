package com.launchcatch.billing.controller;

import com.launchcatch.billing.dto.SettlementMismatchItem;
import com.launchcatch.billing.entity.MismatchCheckType;
import com.launchcatch.billing.service.SettlementMismatchQueryService;
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
@RequestMapping("/v1/admin/settlement-mismatches")
@RequiredArgsConstructor
@Tag(name = "관리자 대사 불일치", description = "관리자가 정산 대사 배치가 찾은 불일치 건을 조회하는 API입니다.")
public class AdminSettlementMismatchController {

    private final SettlementMismatchQueryService queryService;

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
}
