package com.lunchcatch.admin.controller;

import com.lunchcatch.admin.AdminAccessTokenClaims;
import com.lunchcatch.admin.dto.AdminAccountResponse;
import com.lunchcatch.admin.service.AdminAccountQueryService;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 계정")
@RestController
@RequestMapping("/v1/admin/admins")
@RequiredArgsConstructor
class AdminAccountQueryController {

    private final AdminAccountQueryService service;

    @Operation(summary = "관리자 계정 목록 조회", description = "최고 관리자가 활성 계정을 최근 발급순으로 전체 조회한다. ID 또는 이름으로 부분 검색한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공. 결과가 없으면 빈 배열")
    @ApiResponse(responseCode = "401", description = "인증 필요 (AUTH-005)")
    @ApiResponse(responseCode = "403", description = "최고 관리자 권한 필요 (AUTH-006)")
    @ApiResponse(responseCode = "503", description = "토큰 폐기 확인 저장소 장애 (AUTH-002)")
    @GetMapping
    ResponseEnvelope<List<AdminAccountResponse>> list(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return ResponseEnvelope.success(service.list(user.getId(), user.getRole(), user.getIssuedAt(),
                AdminAccessTokenClaims.issuanceVersion(user.getAdditionalClaims()), keyword));
    }
}
