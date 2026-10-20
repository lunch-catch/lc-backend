package com.launchcatch.analytics.query.controller;

import com.launchcatch.analytics.query.controller.dto.CampaignReportSummaryResponse;
import com.launchcatch.analytics.query.service.CampaignReportQueryService;
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
@Tag(name = "분석 캠페인 리포트", description = "관리자가 캠페인 목록에 함께 표시할 소진 포인트와 누적 유효 노출을 조회하는 API입니다.")
public class CampaignReportController {

    private final CampaignReportQueryService service;

    public CampaignReportController(CampaignReportQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/campaign-reports")
    @Operation(
            summary = "관리자 캠페인 리포트 조회",
            description = "요청한 캠페인마다 기간 소진 포인트와 누적 유효 노출을 요청 순서대로 내려줍니다. "
                    + "기간을 생략하면 집계된 전체 기간이고 지정하면 최대 92일입니다. 집계가 없는 캠페인은 두 값이 0입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: campaignIds 누락, 100개 초과, 양수가 아닌 ID입니다. "
                    + "ANALYTICS-001: 기간을 하나만 주었거나 from이 to보다 늦거나 92일 초과입니다.")
    })
    public ResponseEnvelope<CampaignReportSummaryResponse> campaignReports(
            @RequestParam(required = false) String campaignIds,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(CampaignReportSummaryResponse.from(service.query(campaignIds, from, to)));
    }
}
