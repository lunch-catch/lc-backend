package com.launchcatch.analytics.query.controller;

import com.launchcatch.analytics.query.dto.OwnerReportResponse;
import com.launchcatch.analytics.query.service.OwnerReportQueryService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "점주 리포트", description = "점주가 본인의 일별 캠페인 성과와 포인트 소진 내역을 조회합니다.")
public class OwnerReportController {

    private final OwnerReportQueryService service;

    public OwnerReportController(OwnerReportQueryService service) {
        this.service = service;
    }

    // 로그인한 점주의 날짜별 캠페인 성과와 조회 기간의 소진액 합계를 반환한다.
    @GetMapping("/v1/owner/report")
    @Operation(summary = "점주 리포트 조회",
            description = "로그인한 점주의 일별 집계값을 날짜 오름차순으로 반환합니다. 시작일과 종료일을 포함해 최대 30일입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-003: 날짜 누락 또는 형식 오류. ANALYTICS-001: 역전된 기간 또는 30일 초과"),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 로그인 필요"),
            @ApiResponse(responseCode = "403", description = "AUTH-006: OWNER 역할 필요")
    })
    public ResponseEnvelope<OwnerReportResponse> report(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(service.query(user.getId(), from, to));
    }
}
