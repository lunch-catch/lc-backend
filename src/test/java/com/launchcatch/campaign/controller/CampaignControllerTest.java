package com.launchcatch.campaign.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.dto.CampaignCouponConditionRequest;
import com.launchcatch.campaign.dto.CampaignCouponConditionResponse;
import com.launchcatch.campaign.service.CampaignCouponConditionResult;
import com.launchcatch.campaign.service.CampaignCouponConditionService;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class CampaignControllerTest {

    private final CampaignCouponConditionService service = mock(CampaignCouponConditionService.class);
    private final CampaignController controller = new CampaignController(service);
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
}
