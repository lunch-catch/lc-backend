package com.lunchcatch.billing.controller;

import com.lunchcatch.billing.dto.PaymentWebhookRequest;
import com.lunchcatch.billing.service.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/*
 * 결제대행사가 부르는 서버 대 서버 콜백이다. 우리 JWT 가 아니라 공개 경로이고 본문은 믿지 않는다.
 * 처리할 수 없는 요청은 200 으로 답한다. 조회가 막히거나(429) 실패해(5xx) 다시 받아야 할 때만 오류로 답한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "결제 웹훅", description = "결제대행사의 결제 상태 변경 콜백입니다. 승인 확정이 놓친 결제를 맞추는 보조 경로입니다.")
public class PaymentWebhookController {

    private final PaymentWebhookService webhookService;

    @PostMapping("/webhook/payments")
    @Operation(
            summary = "결제 상태 웹훅",
            description = "본문의 결제 키로 결제대행사에 다시 조회해 결제를 확정합니다. 본문의 상태값은 믿지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리했거나, 다시 보내도 소용없는 요청이라 무시했습니다."),
            @ApiResponse(responseCode = "429", description = "같은 주문의 조회가 잠깐 사이에 이미 있었습니다. 결제대행사가 나중에 다시 보냅니다."),
            @ApiResponse(responseCode = "500", description = "결제대행사 조회가 실패했습니다. 결제대행사가 다시 보냅니다.")
    })
    public ResponseEntity<Void> receive(@RequestBody PaymentWebhookRequest request) {
        if (webhookService.handle(request) == PaymentWebhookService.Result.THROTTLED) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.ok().build();
    }

    // 읽을 수 없는 본문은 다시 보내도 같으므로 400 대신 200 으로 끝낸다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> unreadable(HttpMessageNotReadableException e) {
        log.warn("event=BILLING_PAYMENT_WEBHOOK_SKIPPED reason=UNREADABLE exceptionType={}", e.getClass().getSimpleName());
        return ResponseEntity.ok().build();
    }
}
