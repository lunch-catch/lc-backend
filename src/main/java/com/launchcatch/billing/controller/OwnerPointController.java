package com.launchcatch.billing.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.billing.dto.PointBalanceResponse;
import com.launchcatch.billing.service.OwnerPointBalanceService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/points")
@RequiredArgsConstructor
@Tag(name = "점주 포인트", description = "점주가 자신의 잔액과 포인트 변동을 조회하는 API입니다.")
public class OwnerPointController {

    private final OwnerPointBalanceService balanceService;

    @GetMapping("/balance")
    @Operation(
            summary = "잔액 조회",
            description = "원장 합계로 계산한 잔액과 오늘 예약 중인 포인트를 돌려줍니다. 캐시된 값으로 대신하지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "503", description = "BILLING-006: 원장을 읽지 못했습니다.")
    })
    public ResponseEnvelope<PointBalanceResponse> balance(@AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(balanceService.getBalance(owner.getId()));
    }
}
