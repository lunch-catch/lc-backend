package com.lunchcatch.analytics.query.controller;

import com.lunchcatch.analytics.query.controller.dto.MemberUsageStatsResponse;
import com.lunchcatch.analytics.query.service.MemberUsageStatsQueryService;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 회원 사용 통계", description = "회원이 마이페이지 상단에서 자기 찜, 사용, 절약 금액 누적을 보는 API입니다.")
public class MemberUsageStatsController {

    private final MemberUsageStatsQueryService service;

    public MemberUsageStatsController(MemberUsageStatsQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/members/me/usage-stats")
    @Operation(
            summary = "회원 사용 통계 조회",
            description = "본인의 찜, 쿠폰 사용, 확정 할인 금액 누적을 내려줍니다. 전날까지 확정된 일별 값의 합이라 "
                    + "오늘 사용한 쿠폰은 다음 날 반영됩니다. 기록이 없으면 세 값이 모두 0입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    public ResponseEnvelope<MemberUsageStatsResponse> usageStats(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(MemberUsageStatsResponse.from(service.query(user.getId())));
    }
}
