package com.launchcatch.analytics.event.controller;

import com.launchcatch.analytics.event.dto.CampaignFunnelResponse;
import com.launchcatch.analytics.event.service.CampaignFunnelService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "점주 캠페인 분석", description = "이벤트 로그에 기록된 캠페인 성과를 조회합니다.")
public class CampaignFunnelController {

    private final CampaignFunnelService service;

    public CampaignFunnelController(CampaignFunnelService service) {
        this.service = service;
    }

    @GetMapping("/v1/owner/campaigns/{campaignId}/analytics/funnel")
    @Operation(summary = "캠페인 성과 조회", description = "요청 시 이벤트 로그를 집계합니다. 최대 31일이며 "
            + "같은 기간의 발생 건수로 비율을 계산합니다. 이벤트 기록은 지연되거나 누락될 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "요청 형식 오류 또는 ANALYTICS-001: 조회 기간 오류"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "403", description = "점주 권한이 없거나 본인 소유가 아닌 캠페인입니다. 없는 캠페인도 같습니다.")
    })
    public ResponseEnvelope<CampaignFunnelResponse> funnel(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable @Positive Long campaignId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(service.query(user.getId(), campaignId, from, to));
    }
}
