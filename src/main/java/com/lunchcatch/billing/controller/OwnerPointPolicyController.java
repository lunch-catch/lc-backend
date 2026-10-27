package com.lunchcatch.billing.controller;

import com.lunchcatch.billing.dto.PointPolicyResponse;
import com.lunchcatch.billing.service.OwnerPointPolicyService;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/point-policy")
@RequiredArgsConstructor
@Tag(name = "점주 충전 정책", description = "점주 충전 화면이 쓰는 최소 충전 금액과 충전 상품을 조회하는 API입니다.")
public class OwnerPointPolicyController {

    private final OwnerPointPolicyService pointPolicyService;

    @GetMapping
    @Operation(
            summary = "충전 정책 조회",
            description = "오늘 적용 중인 최소 충전 금액과 충전 상품 목록을 돌려줍니다. 노출 단가와 하루 예산 하한은 운영 설정값이라 여기 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    public ResponseEnvelope<PointPolicyResponse> current() {
        return ResponseEnvelope.success(pointPolicyService.getCurrent());
    }
}
