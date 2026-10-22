package com.launchcatch.admin.controller;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.service.AdminLoginService;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 인증", description = "관리자 로그인")
@RestController
@RequestMapping("/v1/admin/auth/tokens")
@RequiredArgsConstructor
class AdminLoginController {
    private final AdminLoginService adminLoginService;
    private final AuthCookieFactory authCookieFactory;

    @Operation(summary = "관리자 로그인", description = "활성 관리자에게 두 토큰을 HttpOnly 쿠키로 발급한다.")
    @ApiResponse(responseCode = "200", description = "로그인 성공")
    @ApiResponse(responseCode = "400", description = "요청 값 검증 실패 (COMMON-002)")
    @ApiResponse(responseCode = "401", description = "계정 없음, 비밀번호 불일치, 비활성 계정 (AUTH-001)")
    @ApiResponse(responseCode = "503", description = "Refresh Token DB 백업 실패 (AUTH-002)")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResponseEnvelope<AdminLoginResponse>> login(@Valid @RequestBody AdminLoginRequest request) {
        AdminLoginResult result = adminLoginService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.refreshTokenCookie(
                        result.refreshToken(), result.response().role(), true).toString())
                .body(ResponseEnvelope.success(result.response()));
    }
}
