package com.lunchcatch.ops.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.ops.controller.dto.BatchExecutionRetryResponse;
import com.lunchcatch.ops.service.BatchExecutionRetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "배치 실행 수동 재실행", description = "관리자가 실패로 확정된 배치 작업을 다시 실행하는 API입니다.")
public class BatchExecutionRetryController {

    private final BatchExecutionRetryService service;

    public BatchExecutionRetryController(BatchExecutionRetryService service) {
        this.service = service;
    }

    @PostMapping("/v1/admin/batch-executions/{jobName}/{businessDate}:retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "배치 수동 재실행",
            description = "FAILED 로 확정된 00:00 묶음 또는 등록된 묶음 밖 작업을 다시 실행합니다. "
                    + "접수 즉시 202를 돌려주고 실제 재실행은 별도로 돕니다. 결과는 감사 로그와 "
                    + "배치 실행 기록으로 확인합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "접수 성공"),
            @ApiResponse(responseCode = "404", description = "OPS-002: 그 작업의 실행 기록이 없습니다."),
            @ApiResponse(responseCode = "409", description = "OPS-003: FAILED 상태가 아니면 재실행할 수 없습니다."),
            @ApiResponse(responseCode = "422", description = "OPS-001: 다시 실행할 수 없는 작업입니다."),
            @ApiResponse(responseCode = "503", description = "OPS-005: 재실행 요청이 몰려 지금은 받을 수 없습니다. 잠시 후 다시 시도해 주세요.")
    })
    public ResponseEnvelope<BatchExecutionRetryResponse> retry(
            @AuthenticationPrincipal CustomUserDetails admin,
            @PathVariable String jobName,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        long runId = service.retry(admin.getId(), jobName, businessDate);
        return ResponseEnvelope.success(BatchExecutionRetryResponse.accepted(runId, jobName, businessDate));
    }
}
