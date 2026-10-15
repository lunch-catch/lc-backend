package com.launchcatch.campaign.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/campaigns")
@RequiredArgsConstructor
@Tag(name = "캠페인 등록", description = "점주가 캠페인을 등록하는 API입니다.")
public class CampaignController {

    private final CampaignCouponConditionService campaignCouponConditionService;

    @PostMapping
    @Operation(
            summary = "캠페인 등록 1단계(쿠폰 조건)",
            description = "그 가게에 작성 중인 DRAFT 캠페인이 없으면 새로 만들고, 있으면 쿠폰 조건만 덮어씁니다. "
                    + "노출 대상은 기본값으로, 예산과 집행 기간은 비워 둔 채로 저장됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새로 만들어 저장 성공."),
            @ApiResponse(responseCode = "200", description = "기존 DRAFT에 덮어써 저장 성공."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 필수값 누락, 허용값 밖, 형식 불일치."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다."),
            @ApiResponse(responseCode = "422", description = "CAMPAIGN-002: 할인율 100 초과. "
                    + "CAMPAIGN-003: 발급 수량 0 이하. CAMPAIGN-004: 이용 가능 시간이 올바르지 않음.")
    })
    public ResponseEntity<ResponseEnvelope<CampaignCouponConditionResponse>> createOrUpdateCouponCondition(
            @Valid @RequestBody CampaignCouponConditionRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        CampaignCouponConditionResult result = campaignCouponConditionService.handle(owner.getId(), request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ResponseEnvelope.success(result.response()));
    }
}
