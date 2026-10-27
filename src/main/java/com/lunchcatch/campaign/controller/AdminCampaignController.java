package com.lunchcatch.campaign.controller;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.dto.AdminCampaignDetailResponse;
import com.lunchcatch.campaign.dto.AdminCampaignListItemResponse;
import com.lunchcatch.campaign.service.AdminCampaignQueryService;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/campaigns")
@RequiredArgsConstructor
@Tag(name = "캠페인 관리", description = "관리자가 전체 캠페인 등록 현황과 상세를 조회하는 API입니다.")
public class AdminCampaignController {

    private final AdminCampaignQueryService adminCampaignQueryService;

    @GetMapping
    @Operation(
            summary = "전체 캠페인 목록 조회",
            description = "모든 점주의 캠페인을 최근 생성 순으로 조회합니다. 가게 ID와 상태로 걸러 볼 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: status가 허용값이 아니거나 storeId 형식이 올바르지 않습니다.")
    })
    public ResponseEnvelope<CursorPageResponse<AdminCampaignListItemResponse>> list(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) CampaignStatus status,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "0") int pageSize) {
        return ResponseEnvelope.success(adminCampaignQueryService.list(storeId, status, pageToken, pageSize));
    }

    @GetMapping("/{campaignId}")
    @Operation(
            summary = "캠페인 상세 조회",
            description = "캠페인 1건의 쿠폰 조건, 노출 대상, 하루 예산과 집행 기간, 상태, 상태 이력, 포스터를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공."),
            @ApiResponse(responseCode = "404", description = "CAMPAIGN-012: 캠페인을 찾을 수 없습니다.")
    })
    public ResponseEnvelope<AdminCampaignDetailResponse> detail(@PathVariable Long campaignId) {
        return ResponseEnvelope.success(adminCampaignQueryService.detail(campaignId));
    }
}
