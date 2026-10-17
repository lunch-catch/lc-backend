package com.launchcatch.analytics.query.controller;

import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.controller.dto.SalesResponse;
import com.launchcatch.analytics.query.service.SalesQueryService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 매출 현황", description = "관리자가 충전액, 소진액, 환불액, 조정액, 미소진 잔액을 일별, 주별, 월별로 보는 API입니다.")
public class SalesController {

    private final SalesQueryService service;

    public SalesController(SalesQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/sales")
    @Operation(
            summary = "매출 현황 조회",
            description = "기간을 일, 주, 월로 묶어 내려줍니다. 흐름 지표는 합계, 미소진 잔액은 구간의 마지막 날 값입니다. "
                    + "집계가 끝나지 않은 구간은 status가 IN_PROGRESS이고 금액이 null입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "ANALYTICS-001: 기간 형식 오류, from이 to보다 늦음, 범위 한도 초과입니다.")
    })
    public ResponseEnvelope<SalesResponse> sales(
            @RequestParam AnalyticsPeriod period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(SalesResponse.from(service.query(period, from, to)));
    }
}
