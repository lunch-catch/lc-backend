package com.launchcatch.billing.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.dto.PaymentOrderRequest;
import com.launchcatch.billing.dto.PaymentOrderResponse;
import com.launchcatch.billing.service.PaymentOrderService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/payments")
@RequiredArgsConstructor
@Tag(name = "점주 결제", description = "점주가 포인트를 충전하려고 주문을 만들고 승인을 확정하고 결제 상태를 조회하는 API입니다.")
public class OwnerPaymentController {

    private final PaymentOrderService orderService;

    @PostMapping
    @Operation(
            summary = "충전 주문 생성",
            description = "결제창을 열기 전에 주문을 만듭니다. 서버가 주문 번호를 발급하고 금액을 저장하며, 승인 확정은 이 저장값으로만 합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "주문 생성 성공. 응답의 orderId 와 amount 로 결제창을 엽니다."),
            @ApiResponse(responseCode = "400", description = "BILLING-001: 최소 충전 금액보다 적습니다. BILLING-002: 충전 상품에 없는 금액입니다. COMMON-002: 금액이 없거나 0 이하입니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-008: 확인 중인 결제가 있어 새로 주문할 수 없습니다.")
    })
    public ResponseEntity<ResponseEnvelope<PaymentOrderResponse>> order(
            @AuthenticationPrincipal CustomUserDetails owner,
            @Valid @RequestBody PaymentOrderRequest request) {
        PaymentOrderResponse response = orderService.order(owner.getId(), request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}
