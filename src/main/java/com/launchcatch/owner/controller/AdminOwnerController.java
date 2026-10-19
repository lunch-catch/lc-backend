package com.launchcatch.owner.controller;

import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.AdminOwnerListQuery;
import com.launchcatch.owner.dto.AdminOwnerResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.service.AdminOwnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/owners")
@RequiredArgsConstructor
@Tag(name = "관리자 점주 조회", description = "관리자와 최고 관리자의 점주 목록 조회·검색 API입니다.")
@SecurityRequirement(name = "adminAccessToken")
public class AdminOwnerController {

    private final AdminOwnerService service;

    @GetMapping
    @Operation(summary = "점주 목록 조회", description = "상태와 가입일 범위로 필터링합니다. 숫자 검색어는 점주 ID 정확히 일치, 나머지는 상호 부분 일치로 검색합니다. 가입일 최신순이며 같은 시각이면 점주 ID 내림차순으로 20건씩 반환합니다. 탈퇴 점주는 상호를 유지하고 사업자번호 전체와 최근 로그인 시각을 숨깁니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "점주 목록과 전체 건수 반환", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 날짜 범위·페이지 검증 실패 또는 상호 검색 결과 1만 건 초과. COMMON-003: 날짜·상태·페이지 형식 오류", content = @Content(schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 인증 필요", content = @Content(schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 관리자 권한 없음", content = @Content(schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<PageResponse<AdminOwnerResponse>> list(
            @Parameter(description = "점주 상태. 생략하면 탈퇴를 포함한 모든 상태를 조회합니다.")
            @RequestParam(required = false) OwnerStatus status,
            @Parameter(description = "가입일 시작 날짜. KST 날짜이며 해당 날짜를 포함합니다.", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @Parameter(description = "가입일 종료 날짜. 해당 날짜를 포함합니다.", example = "2026-10-19")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @Parameter(description = "앞뒤 공백을 제거하며 공백뿐이면 검색하지 않습니다. 숫자만이면 점주 ID를 검색합니다.", example = "런치")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "0부터 시작하는 페이지 번호. 한 페이지는 20건으로 고정합니다.", schema = @Schema(minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEnvelope.success(service.search(new AdminOwnerListQuery(
                status, joinedFrom, joinedTo, keyword, page)));
    }
}
