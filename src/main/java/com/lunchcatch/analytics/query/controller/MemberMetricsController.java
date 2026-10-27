package com.lunchcatch.analytics.query.controller;

import com.lunchcatch.analytics.query.AnalyticsPeriod;
import com.lunchcatch.analytics.query.controller.dto.MemberMetricsResponse;
import com.lunchcatch.analytics.query.service.MemberMetricsQueryService;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 회원 지표", description = "관리자가 신규, 누적, 활성, 탈퇴 회원을 일별, 주별, 월별로 보는 API입니다.")
public class MemberMetricsController {

    private final MemberMetricsQueryService service;

    public MemberMetricsController(MemberMetricsQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/dashboard/members")
    @Operation(
            summary = "회원 지표 조회",
            description = "기간을 일, 주, 월로 묶어 내려줍니다. 신규 가입자는 합계, 누적, 탈퇴, 정지는 구간의 마지막 날 값, "
                    + "활성 회원은 일평균입니다. 집계가 끝나지 않은 구간은 status가 IN_PROGRESS이고 수치가 null입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "ANALYTICS-001: 기간 형식 오류, from이 to보다 늦음, 범위 한도 초과입니다.")
    })
    public ResponseEnvelope<MemberMetricsResponse> members(
            @RequestParam AnalyticsPeriod period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEnvelope.success(MemberMetricsResponse.from(service.query(period, from, to)));
    }
}
