package com.lunchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.dto.OwnerRefundRequestItem;
import com.lunchcatch.billing.dto.RefundPreviewResponse;
import com.lunchcatch.billing.dto.RefundRequestCreateRequest;
import com.lunchcatch.billing.dto.RefundRequestCreateResponse;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.billing.service.OwnerRefundService;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import java.util.List;
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

    @Test
    void 목록_파라미터를_서비스에_그대로_넘긴다() {
        CursorPageResponse<OwnerRefundRequestItem> page = CursorPageResponse.of(List.of(), null);
        when(service.list(7L, "token", 20)).thenReturn(page);

        assertThat(controller.list(OWNER, "token", 20).data()).isSameAs(page);
    }
}
