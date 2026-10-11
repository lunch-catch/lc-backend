package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.DisplayStatus;
import com.launchcatch.billing.dto.PaymentConfirmRequest;
import com.launchcatch.billing.dto.PaymentConfirmResponse;
import com.launchcatch.billing.dto.PaymentDetailResponse;
import com.launchcatch.billing.dto.PaymentHistoryItem;
import com.launchcatch.billing.dto.PaymentOrderRequest;
import com.launchcatch.billing.dto.PaymentOrderResponse;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.service.PaymentConfirmationService;
import com.launchcatch.billing.service.PaymentOrderService;
import com.launchcatch.billing.service.PaymentQueryService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OwnerPaymentControllerTest {

    private final PaymentOrderService orderService = mock(PaymentOrderService.class);
    private final PaymentConfirmationService confirmationService = mock(PaymentConfirmationService.class);
    private final PaymentQueryService queryService = mock(PaymentQueryService.class);
    private final OwnerPaymentController controller =
            new OwnerPaymentController(orderService, confirmationService, queryService);
    private final CustomUserDetails owner = new CustomUserDetails(7L, Role.OWNER);

    @Test
    void 주문은_로그인한_점주의_ID로_만들고_201로_돌려준다() {
        PaymentOrderResponse order = new PaymentOrderResponse(101L, "ord_20261001_abc", 30000);
        when(orderService.order(7L, 30000)).thenReturn(order);

        ResponseEntity<ResponseEnvelope<PaymentOrderResponse>> response =
                controller.order(owner, new PaymentOrderRequest(30000L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data()).isSameAs(order);
    }

    @Test
    void 승인이_확정되면_200으로_돌려준다() {
        PaymentConfirmResponse approved = new PaymentConfirmResponse(
                101L, PaymentStatus.APPROVED, DisplayStatus.COMPLETED, 30000, 30000L);
        when(confirmationService.confirm(7L, 101L, "ord_1", "tgen_1")).thenReturn(approved);

        ResponseEntity<ResponseEnvelope<PaymentConfirmResponse>> response =
                controller.confirm(owner, 101L, new PaymentConfirmRequest("ord_1", "tgen_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).isSameAs(approved);
    }

    @Test
    void 결과를_아직_모르면_202로_돌려준다() {
        PaymentConfirmResponse unknown = new PaymentConfirmResponse(
                101L, PaymentStatus.UNKNOWN, DisplayStatus.CONFIRMING, 30000, null);
        when(confirmationService.confirm(7L, 101L, "ord_1", "tgen_1")).thenReturn(unknown);

        ResponseEntity<ResponseEnvelope<PaymentConfirmResponse>> response =
                controller.confirm(owner, 101L, new PaymentConfirmRequest("ord_1", "tgen_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().data()).isSameAs(unknown);
    }

    @Test
    void 결제_상태는_로그인한_점주의_ID로_조회한다() {
        PaymentDetailResponse detail = new PaymentDetailResponse(
                101L, "ord_1", PaymentStatus.UNKNOWN, DisplayStatus.CONFIRMING, 30000, null, null);
        when(queryService.get(7L, 101L)).thenReturn(detail);

        assertThat(controller.get(owner, 101L).data()).isSameAs(detail);
    }

    @Test
    void 결제_내역은_토큰과_크기를_로그인한_점주의_ID와_함께_넘긴다() {
        CursorPageResponse<PaymentHistoryItem> page = CursorPageResponse.of(List.of(), null);
        when(queryService.list(7L, "token", 50)).thenReturn(page);

        assertThat(controller.list(owner, "token", 50).data()).isSameAs(page);
    }
}
