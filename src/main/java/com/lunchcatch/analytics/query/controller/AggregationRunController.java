package com.lunchcatch.analytics.query.controller;

import com.lunchcatch.analytics.query.controller.dto.AggregationRunResponse;
import com.lunchcatch.analytics.query.service.AggregationRunQueryService;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "분석 집계 실행 조회", description = "관리자가 일별 집계의 실행 상태를 확인하는 API입니다.")
public class AggregationRunController {

    private final AggregationRunQueryService service;

    public AggregationRunController(AggregationRunQueryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/aggregation-runs")
    @Operation(
            summary = "집계 실행 상태 조회",
            description = "영업일의 일별 집계(DAILY_AGGREGATION) 실행 기록을 돌려줍니다. "
                    + "그 영업일에 아직 실행된 적이 없으면 data가 null입니다."
    )
    public ResponseEnvelope<AggregationRunResponse> findRun(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEnvelope.success(
                service.findRun(businessDate).map(AggregationRunResponse::from).orElse(null));
    }
}
