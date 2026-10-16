package com.launchcatch.campaign.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.dto.CampaignDetailResponse;
import com.launchcatch.campaign.dto.CampaignListItemResponse;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.dto.CampaignUpdateResponse;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.campaign.service.CampaignOwnerQueryService;
import com.launchcatch.campaign.service.CampaignTargetAudienceService;
import com.launchcatch.global.response.CursorPageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/campaigns")
@RequiredArgsConstructor
@Tag(name = "캠페인 등록과 조회", description = "점주가 캠페인을 등록하고 조회하는 API입니다.")
public class CampaignController {

    private final CampaignCouponConditionService campaignCouponConditionService;
    private final CampaignTargetAudienceService campaignTargetAudienceService;
    private final CampaignOwnerQueryService campaignOwnerQueryService;

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

    @PatchMapping("/{campaignId}")
    @Operation(
            summary = "캠페인 등록 3·4단계(노출 대상, 하루 예산과 집행 기간)",
            description = "DRAFT 상태에서 노출 대상(targetRadius, targetGender, targetAgeGroups)을 바꿉니다. "
                    + "dailyBudget, startDate, endDate는 아직 지원하지 않아 보내면 거부됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 필수값 누락, 허용값 밖, 형식 불일치. "
                    + "dailyBudget/startDate/endDate를 보낸 경우도 포함."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다. "
                    + "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다."),
            @ApiResponse(responseCode = "409", description = "CAMPAIGN-006: DRAFT가 아닌 캠페인입니다.")
    })
    public ResponseEnvelope<CampaignUpdateResponse> updateTargetAudience(
            @Parameter(description = "수정할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Valid @RequestBody CampaignUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignTargetAudienceService.handle(owner.getId(), campaignId, request));
    }

    @GetMapping
    @Operation(
            summary = "캠페인 목록 조회",
            description = "점주 본인 소유 캠페인을 최근 생성 순으로 커서 페이지네이션으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공.")
    })
    public ResponseEnvelope<CursorPageResponse<CampaignListItemResponse>> list(
            @Parameter(description = "다음 페이지 커서입니다. 비우면 처음부터 조회합니다.")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "한 번에 받을 개수입니다. 기본 20, 최대 100입니다.")
            @RequestParam(required = false, defaultValue = "0") int pageSize,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignOwnerQueryService.list(owner.getId(), pageToken, pageSize));
    }

    @GetMapping("/{campaignId}")
    @Operation(
            summary = "캠페인 상세 조회",
            description = "캠페인 1건의 쿠폰 조건, 노출 대상, 하루 예산과 집행 기간, 상태를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다.")
    })
    public ResponseEnvelope<CampaignDetailResponse> detail(
            @Parameter(description = "조회할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignOwnerQueryService.detail(owner.getId(), campaignId));
    }
}
