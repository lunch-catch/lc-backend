package com.launchcatch.admin.controller;

import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.dto.AdminRegistrationResponse;
import com.launchcatch.admin.service.AdminRegistrationService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 계정", description = "최고관리자가 다른 관리자 계정을 발급한다")
@RestController
@RequestMapping("/v1/admin/admins")
@RequiredArgsConstructor
class AdminRegistrationController {

    private final AdminRegistrationService adminRegistrationService;

    @Operation(
            summary = "관리자 계정 발급",
            description = "최고관리자만 사용할 수 있다. 초기 비밀번호는 BCrypt 해시로만 저장한다.")
    @ApiResponse(responseCode = "201", description = "계정 발급 성공")
    @ApiResponse(responseCode = "403", description = "최고관리자 권한 필요 (AUTH-006)")
    @ApiResponse(responseCode = "503", description = "토큰 폐기 확인 저장소 장애 (AUTH-002)")
    @ApiResponse(responseCode = "409", description = "로그인 아이디 중복 (ADMIN-001)")
    @PostMapping
    ResponseEntity<ResponseEnvelope<AdminRegistrationResponse>> register(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody AdminRegistrationRequest request) {
        AdminRegistrationResponse response = adminRegistrationService.register(
                userDetails.getId(), userDetails.getRole(), userDetails.getIssuedAt(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}