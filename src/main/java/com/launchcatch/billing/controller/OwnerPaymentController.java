package com.launchcatch.billing.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.dto.PaymentConfirmRequest;
import com.launchcatch.billing.dto.PaymentConfirmResponse;
import com.launchcatch.billing.dto.PaymentOrderRequest;
import com.launchcatch.billing.dto.PaymentOrderResponse;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.service.PaymentConfirmationService;
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
import org.springframework.web.bind.annotation.PathVariable;
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
    private final PaymentConfirmationService confirmationService;

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

    /*
     * 결과를 아직 확정하지 못한 결제(UNKNOWN)는 202 로 답한다.
     * 이 API 에 한정한 쓰임이고, 프론트는 응답의 paymentId 로 상태 조회를 폴링한다.
     */
    @PostMapping("/{paymentId}:confirm")
    @Operation(
            summary = "승인 확정과 충전",
            description = "결제창의 성공 URL 이 돌려준 paymentKey 를 pgTid 로 받아 결제대행사에 승인을 요청하고, 승인되면 포인트를 충전합니다. "
                    + "금액은 주문 때 저장한 값으로만 요청합니다. 같은 주문으로 다시 불러도 충전은 한 번입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승인과 충전 완료, 또는 이미 승인된 결제입니다."),
            @ApiResponse(responseCode = "202", description = "결제 결과를 아직 확정하지 못했습니다(status UNKNOWN, displayStatus CONFIRMING). 상태 조회를 폴링합니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: orderId 나 pgTid 가 없거나 너무 깁니다."),
            @ApiResponse(responseCode = "404", description = "BILLING-004: 내 결제 중에 없는 결제입니다."),
            @ApiResponse(responseCode = "409", description = "BILLING-017: orderId 가 저장된 주문과 다르거나 pgTid 가 다른 결제에 이미 쓰였습니다. BILLING-009: 이미 취소된 주문이라 새로 주문해야 합니다."),
            @ApiResponse(responseCode = "422", description = "BILLING-003: 결제가 실패했거나 승인 전에 만료되어 충전되지 않았습니다."),
            @ApiResponse(responseCode = "503", description = "BILLING-007: 승인 요청이 결제대행사에 가지 않았습니다. 같은 주문으로 다시 시도할 수 있습니다.")
    })
    public ResponseEntity<ResponseEnvelope<PaymentConfirmResponse>> confirm(
            @AuthenticationPrincipal CustomUserDetails owner,
            @PathVariable Long paymentId,
            @Valid @RequestBody PaymentConfirmRequest request) {
        PaymentConfirmResponse response =
                confirmationService.confirm(owner.getId(), paymentId, request.orderId(), request.pgTid());
        HttpStatus status = response.status() == PaymentStatus.UNKNOWN ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ResponseEnvelope.success(response));
    }
}
