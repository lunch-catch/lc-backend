package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.RefundPreviewResponse;
import com.launchcatch.billing.dto.RefundRequestCreateRequest;
import com.launchcatch.billing.dto.RefundRequestCreateResponse;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.service.OwnerRefundService;
import com.launchcatch.global.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OwnerRefundControllerTest {

    private static final CustomUserDetails OWNER = new CustomUserDetails(7L, Role.OWNER);
    private static final RefundRequestCreateResponse CREATED =
            new RefundRequestCreateResponse(31L, 12000L, 0L, RefundStatus.REQUESTED);

    private final OwnerRefundService service = mock(OwnerRefundService.class);
    private final OwnerRefundController controller = new OwnerRefundController(service);

    @Test
    void 미리보기는_로그인한_점주의_것을_돌려준다() {
        RefundPreviewResponse preview = new RefundPreviewResponse(12000L, 0L, 12000L, true, null);
        when(service.preview(7L)).thenReturn(preview);

        assertThat(controller.preview(OWNER).data()).isSameAs(preview);
    }

    @Test
    void 요청은_201로_답하고_사유를_넘긴다() {
        when(service.request(7L, "서비스 중단")).thenReturn(CREATED);

        ResponseEntity<ResponseEnvelope<RefundRequestCreateResponse>> response =
                controller.request(OWNER, new RefundRequestCreateRequest("서비스 중단"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data()).isSameAs(CREATED);
    }

    @Test
    void 본문이_없으면_사유_없이_요청한다() {
        when(service.request(7L, null)).thenReturn(CREATED);

        assertThat(controller.request(OWNER, null).getBody().data()).isSameAs(CREATED);
    }
}
