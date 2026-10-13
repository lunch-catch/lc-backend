package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.launchcatch.billing.dto.PaymentWebhookRequest;
import com.launchcatch.billing.service.PaymentWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

class PaymentWebhookControllerTest {

    private final PaymentWebhookService webhookService = mock(PaymentWebhookService.class);
    private final PaymentWebhookController controller = new PaymentWebhookController(webhookService);

    @Test
    void 웹훅을_서비스에_넘기고_200으로_답한다() {
        PaymentWebhookRequest request = new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED",
                new PaymentWebhookRequest.Data("tgen_1", "ord_1"));

        ResponseEntity<Void> response = controller.receive(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookService).handle(request);
    }

    @Test
    void 읽을_수_없는_본문도_다시_보내도_소용없으므로_200으로_답한다() {
        ResponseEntity<Void> response = controller.unreadable(mock(HttpMessageNotReadableException.class));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
