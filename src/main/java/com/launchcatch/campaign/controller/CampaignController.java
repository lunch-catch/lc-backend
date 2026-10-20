package com.launchcatch.campaign.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.dto.CampaignActivateResponse;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.dto.CampaignDetailResponse;
import com.launchcatch.campaign.dto.CampaignListItemResponse;
import com.launchcatch.campaign.dto.CampaignStatusResponse;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.dto.CampaignUpdateResponse;
import com.launchcatch.campaign.service.CampaignActivationService;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.campaign.service.CampaignOwnerQueryService;
import com.launchcatch.campaign.service.CampaignPauseResumeService;
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
    private final CampaignPauseResumeService campaignPauseResumeService;
    private final CampaignActivationService campaignActivationService;

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
            description = "DRAFT 상태에서 노출 대상(targetRadius, targetGender, targetAgeGroups)과 "
                    + "하루 예산·집행 기간(dailyBudget, startDate, endDate)을 바꿉니다. ACTIVE 상태에서는 "
                    + "dailyBudget만 바꿀 수 있고, 다음 영업일 00:00부터 적용되는 예약 값으로만 저장됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 필수값 누락(묶음 중 일부만 보낸 경우 포함), "
                    + "허용값 밖, 형식 불일치."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다. "
                    + "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다."),
            @ApiResponse(responseCode = "409", description = "CAMPAIGN-006: 지금 캠페인 상태에서는 그 필드를 바꿀 수 없습니다."),
            @ApiResponse(responseCode = "422", description = "CAMPAIGN-007: 하루 예산이 최소 하루 예산 미만입니다. "
                    + "CAMPAIGN-008: 집행 기간이 1일 미만이거나 종료일이 시작일보다 이전입니다. "
                    + "CAMPAIGN-009: 집행 시작일이 오늘 다음 날 이전입니다.")
    })
    public ResponseEnvelope<CampaignUpdateResponse> updateTargetAudience(
            @Parameter(description = "수정할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Valid @RequestBody CampaignUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignTargetAudienceService.handle(owner.getId(), campaignId, request));
    }

    @PostMapping("/{campaignId}:activate")
    @Operation(
            summary = "캠페인 활성화",
            description = "DRAFT 상태 캠페인의 활성화를 요청합니다. 포스터가 있는지 확인한 뒤 자동 검수를 "
                    + "거칩니다. 검수를 통과하면 SCHEDULED로 바뀌고, 실패하면 DRAFT로 남아 사유를 돌려줍니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공. 검수 통과면 SCHEDULED, 실패면 DRAFT와 사유."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다. "
                    + "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다."),
            @ApiResponse(responseCode = "404", description = "POSTER-021: 이 캠페인에 저장된 포스터가 없습니다."),
            @ApiResponse(responseCode = "409", description = "CAMPAIGN-006: DRAFT 상태가 아니라 활성화할 수 없습니다.")
    })
    public ResponseEnvelope<CampaignActivateResponse> activate(
            @Parameter(description = "활성화할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignActivationService.activate(owner.getId(), campaignId));
    }

    @PostMapping("/{campaignId}:pause")
    @Operation(
            summary = "캠페인 중단",
            description = "ACTIVE 상태의 캠페인을 점주가 직접 중단합니다. 이미 점주 중단으로 PAUSED면 멱등하게 "
                    + "다시 200을 돌려줍니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "중단 성공."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다. "
                    + "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다."),
            @ApiResponse(responseCode = "409", description = "CAMPAIGN-006: ACTIVE 상태가 아니라 중단할 수 없습니다.")
    })
    public ResponseEnvelope<CampaignStatusResponse> pause(
            @Parameter(description = "중단할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignPauseResumeService.pause(owner.getId(), campaignId));
    }

    @PostMapping("/{campaignId}:resume")
    @Operation(
            summary = "캠페인 재개",
            description = "PAUSED 상태의 캠페인을 점주가 직접 재개합니다. 이미 ACTIVE면 멱등하게 다시 200을 "
                    + "돌려줍니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재개 성공."),
            @ApiResponse(responseCode = "403", description = "CAMPAIGN-001: 점주 상태가 ACTIVE가 아닙니다. "
                    + "CAMPAIGN-005: 그 campaignId가 없거나 본인 소유가 아닙니다."),
            @ApiResponse(responseCode = "409", description = "CAMPAIGN-006: PAUSED 상태가 아니라 재개할 수 없습니다.")
    })
    public ResponseEnvelope<CampaignStatusResponse> resume(
            @Parameter(description = "재개할 캠페인 ID입니다.") @PathVariable Long campaignId,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(campaignPauseResumeService.resume(owner.getId(), campaignId));
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
