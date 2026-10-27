package com.lunchcatch.ops.controller;

import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.ops.dto.BatchExecutionHistoryItem;
import com.lunchcatch.ops.service.BatchExecutionHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * 기간과 상태를 문자열로 받는다. LocalDate 나 enum 으로 받으면 바꾸다 실패하거나 빠졌을 때 전역 처리기가
 * COMMON-003 으로 답하는데, 명세는 기간 위반을 OPS-004, 형식 위반을 COMMON-002 로 정했다.
 */
@RestController
@Tag(name = "배치 실행 이력 조회", description = "관리자가 영업일 범위 안의 배치 실행 기록을 조회하는 API입니다.")
public class BatchExecutionHistoryController {

    private final BatchExecutionHistoryService service;

    public BatchExecutionHistoryController(BatchExecutionHistoryService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/batch-executions")
    @Operation(
            summary = "배치 실행 이력 조회",
            description = "영업일 범위 안의 배치 실행 기록을 (작업명, 영업일)마다 한 항목으로 돌려줍니다. "
                    + "영업일 내림차순, 같은 날 runId 내림차순이고 커서 방식입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공. 조건에 맞는 기록이 없으면 items가 빈 배열입니다."),
            @ApiResponse(responseCode = "400", description = "OPS-004: 조회 기간이 올바르지 않습니다. "
                    + "COMMON-002: 날짜 형식, status 값, pageSize 범위, pageToken이 올바르지 않습니다.")
    })
    public ResponseEnvelope<CursorPageResponse<BatchExecutionHistoryItem>> search(
            @Parameter(description = "조회할 첫 영업일(YYYY-MM-DD). 필수입니다.")
            @RequestParam(required = false) String from,
            @Parameter(description = "조회할 마지막 영업일(YYYY-MM-DD). 필수이고 from부터 세어 31일까지입니다.")
            @RequestParam(required = false) String to,
            @Parameter(description = "작업명. 정확히 같은 것만 담습니다.")
            @RequestParam(required = false) String jobName,
            @Parameter(description = "RUNNING, SUCCESS, FAILED 중 하나입니다.")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "기본 20, 최대 100입니다.")
            @RequestParam(required = false) Integer pageSize) {
        return ResponseEnvelope.success(service.search(from, to, jobName, status, pageToken, pageSize));
    }
}
