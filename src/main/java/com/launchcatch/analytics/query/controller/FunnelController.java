package com.launchcatch.analytics.query.controller;

import com.launchcatch.analytics.query.AnalyticsPeriod;
import com.launchcatch.analytics.query.controller.dto.FunnelResponse;
import com.launchcatch.analytics.query.service.FunnelQueryService;
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
@Tag(name = "분석 퍼널", description = "관리자가 노출, 찜, 발급, 사용의 전환 흐름을 일별, 주별, 월별로 보는 API입니다.")
public class FunnelController {

    private final FunnelQueryService service;

    public FunnelController(FunnelQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/dashboard/funnel")
    @Operation(
            summary = "퍼널 조회",
            description = "기간을 일, 주, 월로 묶어 내려줍니다. 건수는 합계이고 유효 노출 비율은 합산한 값으로 다시 계산합니다. "
                    + "집계가 끝나지 않은 구간은 status가 IN_PROGRESS이고 수치가 null입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "ANALYTICS-001: 기간 형식 오류, from이 to보다 늦음, 범위 한도 초과입니다.")
    })
    public ResponseEnvelope<FunnelResponse> funnel(
            @RequestParam AnalyticsPeriod period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(FunnelResponse.from(service.query(period, from, to)));
    }
}
