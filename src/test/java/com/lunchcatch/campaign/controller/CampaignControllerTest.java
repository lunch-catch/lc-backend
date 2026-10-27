package com.lunchcatch.campaign.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.DiscountTargetType;
import com.lunchcatch.campaign.contract.DiscountType;
import com.lunchcatch.campaign.contract.TargetGender;
import com.lunchcatch.campaign.dto.CampaignActivateResponse;
import com.lunchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.lunchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.lunchcatch.campaign.dto.CampaignDetailResponse;
import com.lunchcatch.campaign.dto.CampaignListItemResponse;
import com.lunchcatch.campaign.dto.CampaignStatusResponse;
import com.lunchcatch.campaign.dto.CampaignUpdateRequest;
import com.lunchcatch.campaign.dto.CampaignUpdateResponse;
import com.lunchcatch.campaign.service.CampaignActivationService;
import com.lunchcatch.campaign.service.CampaignCancelService;
import com.lunchcatch.campaign.service.CampaignCouponConditionResult;
import com.lunchcatch.campaign.service.CampaignCouponConditionService;
import com.lunchcatch.campaign.service.CampaignOwnerQueryService;
import com.lunchcatch.campaign.service.CampaignPauseResumeService;
import com.lunchcatch.campaign.service.CampaignTargetAudienceService;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
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
    private final CampaignActivationService activationService = mock(CampaignActivationService.class);
    private final CampaignCancelService cancelService = mock(CampaignCancelService.class);
    private final CampaignController controller = new CampaignController(
            service, targetAudienceService, ownerQueryService, pauseResumeService, activationService, cancelService);
    private final CustomUserDetails owner = new CustomUserDetails(7L, Role.OWNER);

    private final CampaignCouponConditionRequest request = new CampaignCouponConditionRequest(
            17L, DiscountTargetType.ALL, DiscountType.AMOUNT, 15, null,
            100, LocalTime.of(11, 30), LocalTime.of(13, 0), null, null);

    @Test
    void 취소는_점주와_캠페인_ID로_서비스에_맡긴다() {
        CampaignStatusResponse response = new CampaignStatusResponse(501L, CampaignStatus.DRAFT);
        when(cancelService.cancel(7L, 501L)).thenReturn(response);

        assertThat(controller.cancel(501L, owner).data()).isSameAs(response);
    }

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
    void 활성화는_활성화_서비스_결과를_그대로_반환한다() {
        CampaignActivateResponse response = new CampaignActivateResponse(501L, CampaignStatus.SCHEDULED, List.of());
        when(activationService.activate(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignActivateResponse> result = controller.activate(501L, owner);

        verify(activationService).activate(7L, 501L);
        assertThat(result.data()).isSameAs(response);
    }

    @Test
    void 종료는_중단_재개_서비스_결과를_그대로_반환한다() {
        CampaignStatusResponse response = new CampaignStatusResponse(501L, CampaignStatus.ENDED);
        when(pauseResumeService.end(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignStatusResponse> result = controller.end(501L, owner);

        verify(pauseResumeService).end(7L, 501L);
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
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null, 100,
                LocalTime.of(11, 30), LocalTime.of(13, 0), null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null, LocalDateTime.now(), LocalDateTime.now());
        when(ownerQueryService.detail(7L, 501L)).thenReturn(response);

        ResponseEnvelope<CampaignDetailResponse> result = controller.detail(501L, owner);

        verify(ownerQueryService).detail(7L, 501L);
        assertThat(result.data()).isSameAs(response);
    }
}
