package com.launchcatch.analytics.batch.controller;

import com.launchcatch.analytics.batch.controller.dto.RecalculationRequest;
import com.launchcatch.analytics.batch.controller.dto.RecalculationResponse;
import com.launchcatch.analytics.batch.service.AggregationRecalculationService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 집계 재계산", description = "관리자가 특정 영업일의 일별 집계를 다시 돌리는 API입니다.")
public class AggregationRecalculationController {

    private final AggregationRecalculationService service;

    public AggregationRecalculationController(AggregationRecalculationService service) {
        this.service = service;
    }

    @PostMapping("/v1/admin/aggregations:recalculate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "집계 재계산",
            description = "어제 이전의 영업일을 다시 집계합니다. 실행 기록을 잡은 뒤 바로 202를 돌려주고 "
                    + "집계는 따로 돕니다. 결과는 집계 실행 조회로 확인합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "접수 성공"),
            @ApiResponse(responseCode = "409", description = "ANALYTICS-003: 그 영업일의 집계가 이미 실행 중입니다."),
            @ApiResponse(responseCode = "422", description = "ANALYTICS-002: 오늘, 미래, 보존 기간 13개월보다 오래된 영업일입니다.")
    })
    public ResponseEnvelope<RecalculationResponse> recalculate(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestBody RecalculationRequest request) {
        LocalDate businessDate = request == null ? null : request.businessDate();
        long runId = service.request(admin.getId(), businessDate);
        return ResponseEnvelope.success(RecalculationResponse.running(runId, businessDate));
    }
}
