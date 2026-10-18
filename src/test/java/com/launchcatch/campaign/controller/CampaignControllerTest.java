package com.launchcatch.campaign.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.dto.CampaignDetailResponse;
import com.launchcatch.campaign.dto.CampaignListItemResponse;
import com.launchcatch.campaign.dto.CampaignStatusResponse;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.dto.CampaignUpdateResponse;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.campaign.service.CampaignOwnerQueryService;
import com.launchcatch.campaign.service.CampaignPauseResumeService;
import com.launchcatch.campaign.service.CampaignTargetAudienceService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CampaignControllerTest {

    private final CampaignCouponConditionService service = mock(CampaignCouponConditionService.class);
    private final CampaignTargetAudienceService targetAudienceService = mock(CampaignTargetAudienceService.class);
    private final CampaignOwnerQueryService ownerQueryService = mock(CampaignOwnerQueryService.class);
    private final CampaignPauseResumeService pauseResumeService = mock(CampaignPauseResumeService.class);
    private final CampaignController controller =
            new CampaignController(service, targetAudienceService, ownerQueryService, pauseResumeService);
    private final CustomUserDetails owner = new CustomUserDetails(7L, Role.OWNER);

    private final CampaignCouponConditionRequest request = new CampaignCouponConditionRequest(
            17L, DiscountTargetType.ALL, DiscountType.PERCENT, 15, null,
            100, LocalTime.of(11, 30), LocalTime.of(13, 0), null, null);

    @Test
    void 새로_만들면_201을_반환한다() {
        CampaignCouponConditionResponse response = new CampaignCouponConditionResponse(501L, CampaignStatus.DRAFT);
        when(service.handle(7L, request)).thenReturn(new CampaignCouponConditionResult(response, true));

        ResponseEntity<ResponseEnvelope<CampaignCouponConditionResponse>> result =
                controller.createOrUpdateCouponCondition(request, owner);

        verify(service).handle(7L, request);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().data()).isSameAs(response);
    }

    @Test
    void 기존_DRAFT를_덮어쓰면_200을_반환한다() {
        CampaignCouponConditionResponse response = new CampaignCouponConditionResponse(501L, CampaignStatus.DRAFT);
        when(service.handle(7L, request)).thenReturn(new CampaignCouponConditionResult(response, false));

        ResponseEntity<ResponseEnvelope<CampaignCouponConditionResponse>> result =
                controller.createOrUpdateCouponCondition(request, owner);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().data()).isSameAs(response);
    }

    @Test
    void 노출_대상_수정에_성공하면_200을_반환한다() {
        CampaignUpdateRequest targetAudienceRequest = new CampaignUpdateRequest(
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S), null, null, null);
        CampaignUpdateResponse response = new CampaignUpdateResponse(
                501L, 1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S), null, null, null);
        when(targetAudienceService.handle(7L, 501L, targetAudienceRequest)).thenReturn(response);

        ResponseEnvelope<CampaignUpdateResponse> result =
                controller.updateTargetAudience(501L, targetAudienceRequest, owner);

        verify(targetAudienceService).handle(7L, 501L, targetAudienceRequest);
        assertThat(result.data()).isSameAs(response);
    }

    @Test
    void 중단은_중단_서비스_결과를_그대로_반환한다() {
        CampaignStatusResponse response = new CampaignStatusResponse(501L, CampaignStatus.PAUSED);
        when(pauseResumeService.pause(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignStatusResponse> result = controller.pause(501L, owner);

        verify(pauseResumeService).pause(7L, 501L);
        assertThat(result.data()).isSameAs(response);
    }

    @Test
    void 재개는_재개_서비스_결과를_그대로_반환한다() {
        CampaignStatusResponse response = new CampaignStatusResponse(501L, CampaignStatus.ACTIVE);
        when(pauseResumeService.resume(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignStatusResponse> result = controller.resume(501L, owner);

        verify(pauseResumeService).resume(7L, 501L);
        assertThat(result.data()).isSameAs(response);
    }

    @Test
    void 목록_조회는_조회_서비스_결과를_그대로_반환한다() {
        CursorPageResponse<CampaignListItemResponse> page = CursorPageResponse.of(List.of(), null);
        when(ownerQueryService.list(7L, "token", 10)).thenReturn(page);

        ResponseEnvelope<CursorPageResponse<CampaignListItemResponse>> result =
                controller.list("token", 10, owner);

        verify(ownerQueryService).list(7L, "token", 10);
        assertThat(result.data()).isSameAs(page);
    }

    @Test
    void 상세_조회는_조회_서비스_결과를_그대로_반환한다() {
        CampaignDetailResponse response = new CampaignDetailResponse(
                501L, 20L, CampaignStatus.ACTIVE, null,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null, 100,
                LocalTime.of(11, 30), LocalTime.of(13, 0), null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null, LocalDateTime.now(), LocalDateTime.now());
        when(ownerQueryService.detail(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignDetailResponse> result = controller.detail(501L, owner);

        verify(ownerQueryService).detail(7L, 501L);
        assertThat(result.data()).isSameAs(response);
    }
}
