package com.launchcatch.admin.controller;

import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.service.AdminTokenService;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 인증", description = "관리자 토큰 재발급")
@RestController
@RequiredArgsConstructor
class AdminTokenController {
    private final AdminTokenService tokens;
    private final AuthCookieFactory cookies;

    @Operation(summary = "관리자 토큰 재발급", description = "Refresh Token으로 두 토큰을 회전한다.")
    @ApiResponse(responseCode = "200", description = "재발급 성공")
    @ApiResponse(responseCode = "401", description = "유효하지 않은 토큰 (AUTH-003), 재사용 (AUTH-004)")
    @ApiResponse(responseCode = "503", description = "저장소 장애 또는 회전 결과 미확정 (AUTH-002)")
    @PostMapping("/v1/admin/auth/tokens:refresh")
    ResponseEntity<ResponseEnvelope<AdminLoginResponse>> reissue(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        AdminLoginResult result = tokens.reissue(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.accessTokenCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.refreshTokenCookie(
                        result.refreshToken(), result.response().role(), true).toString())
                .body(ResponseEnvelope.success(result.response()));
    }
}
