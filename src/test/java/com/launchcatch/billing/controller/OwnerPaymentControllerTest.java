package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.PaymentOrderRequest;
import com.launchcatch.billing.dto.PaymentOrderResponse;
import com.launchcatch.billing.service.PaymentOrderService;
import com.launchcatch.global.response.ResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class OwnerPaymentControllerTest {

    private final PaymentOrderService orderService = mock(PaymentOrderService.class);
    private final OwnerPaymentController controller = new OwnerPaymentController(orderService);
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
}
