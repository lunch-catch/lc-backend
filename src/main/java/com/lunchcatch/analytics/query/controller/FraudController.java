package com.lunchcatch.analytics.query.controller;

import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.query.CampaignWarningType;
import com.lunchcatch.analytics.query.controller.dto.CampaignWarningResponse;
import com.lunchcatch.analytics.query.controller.dto.InvalidImpressionResponse;
import com.lunchcatch.analytics.query.service.CampaignWarningQueryService;
import com.lunchcatch.analytics.query.service.InvalidImpressionQueryService;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 부정 관리", description = "관리자가 무효 노출과 캠페인 부정 경고를 조회하는 API입니다.")
public class FraudController {

    private final InvalidImpressionQueryService invalidImpressions;
    private final CampaignWarningQueryService campaignWarnings;

    public FraudController(
            InvalidImpressionQueryService invalidImpressions, CampaignWarningQueryService campaignWarnings) {
        this.invalidImpressions = invalidImpressions;
        this.campaignWarnings = campaignWarnings;
    }

    @GetMapping("/v1/admin/fraud/invalid-impressions")
    @Operation(
            summary = "무효 노출 사유별 집계 조회",
            description = "기간 안의 무효 노출을 회원, 캠페인, 사유 단위로 내려줍니다. summaryByReason은 필터 조건 전체의 사유별 합계입니다. "
                    + "기간은 필수이고 최대 31일입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "ANALYTICS-001: 기간 누락, from이 to보다 늦음, 31일 초과입니다.")
    })
    public ResponseEnvelope<InvalidImpressionResponse> invalidImpressions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) InvalidReasonCode reasonCode,
            @RequestParam(required = false) Long campaignId,
            @RequestParam(required = false) Long memberId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "count") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEnvelope.success(InvalidImpressionResponse.from(invalidImpressions.query(
                from, to, reasonCode, campaignId, memberId, page, size, sortBy, sortDir)));
    }

    @GetMapping("/v1/admin/fraud/campaign-warnings")
    @Operation(
            summary = "캠페인 경고 조회",
            description = "무효 비율 경고와 집중 경고가 걸린 캠페인을 내려줍니다. type을 생략하면 둘 중 하나라도 걸린 캠페인을 모두 내려줍니다. "
                    + "기간은 필수이고 최대 31일입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "ANALYTICS-001: 기간 누락, from이 to보다 늦음, 31일 초과입니다.")
    })
    public ResponseEnvelope<CampaignWarningResponse> campaignWarnings(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) CampaignWarningType type,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "invalidRatio") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEnvelope.success(CampaignWarningResponse.from(
                campaignWarnings.query(from, to, type, page, size, sortBy, sortDir)));
    }
}
