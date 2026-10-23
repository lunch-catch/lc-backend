package com.launchcatch.member.controller;

import com.launchcatch.global.response.PageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.AdminMemberListQuery;
import com.launchcatch.member.dto.AdminMemberResponse;
import com.launchcatch.member.service.AdminMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/members")
@RequiredArgsConstructor
@Tag(name = "관리자 회원 조회", description = "관리자와 최고 관리자의 회원 목록 조회·검색 API입니다.")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping
    @Operation(summary = "회원 목록 조회", description = "회원 상태, 가입일 범위, 닉네임 접두사로 검색합니다. 검색어의 앞뒤 공백을 제거하고 공백뿐이면 검색 조건에서 제외합니다. 숫자도 닉네임으로 검색하며 특수 문자는 문자 그대로 처리합니다. 정렬값이 같으면 회원 식별자를 같은 방향으로 정렬하고, 마지막 로그인 시각이 없는 회원은 항상 맨 뒤에 둡니다. 결과가 없으면 items는 빈 배열입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원 목록 및 전체 건수 반환", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 날짜 범위·페이지·정렬 검증 실패. COMMON-003: 날짜 형식 또는 상태 값 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 관리자 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 관리자 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<PageResponse<AdminMemberResponse>> list(
            @Parameter(description = "회원 상태. 생략하면 모든 상태를 조회합니다.", example = "ACTIVE")
            @RequestParam(required = false) MemberStatus status,
            @Parameter(description = "가입일 시작 날짜. 한국 시각 기준이며 해당 날짜를 포함합니다.", example = "2026-10-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedFrom,
            @Parameter(description = "가입일 종료 날짜. 해당 날짜를 포함하며 시작 날짜보다 빠를 수 없습니다.", example = "2026-10-13")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedTo,
            @Parameter(description = "닉네임 접두사 검색어입니다.", example = "점심")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "0부터 시작하는 페이지 번호입니다.", schema = @Schema(minimum = "0", defaultValue = "0"), example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 회원 수. 1~100입니다.", schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"), example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "정렬 기준입니다.", schema = @Schema(allowableValues = {"joinedAt", "lastLoginAt", "nickname", "memberId"}, defaultValue = "joinedAt"))
            @RequestParam(required = false) String sortBy,
            @Parameter(description = "정렬 방향입니다.", schema = @Schema(allowableValues = {"asc", "desc"}, defaultValue = "desc"))
            @RequestParam(required = false) String sortDir) {
        return ResponseEnvelope.success(adminMemberService.search(new AdminMemberListQuery(
                status, joinedFrom, joinedTo, keyword, page, size, sortBy, sortDir)));
    }
}
