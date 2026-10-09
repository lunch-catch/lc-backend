package com.launchcatch.admin.controller;

import com.launchcatch.admin.service.AdminLogoutService;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 인증", description = "관리자 로그인 및 로그아웃")
@RestController
@RequestMapping("/v1/admin/auth/tokens")
@RequiredArgsConstructor
class AdminLogoutController {
    private final AdminLogoutService adminLogoutService;
    private final AuthCookieFactory authCookieFactory;

    @Operation(summary = "관리자 로그아웃", description = "현재 관리자의 토큰을 폐기하고 인증 쿠키를 삭제한다.")
    @ApiResponse(responseCode = "204", description = "로그아웃 성공")
    @ApiResponse(responseCode = "401", description = "유효한 관리자 인증 필요 (AUTH-005)")
    @ApiResponse(responseCode = "403", description = "관리자 권한 필요 (AUTH-006)")
    @ApiResponse(responseCode = "503", description = "필수 토큰 폐기 실패 (AUTH-002)")
    @DeleteMapping
    ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails principal) {
        adminLogoutService.logout(principal.getId(), principal.getRole());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie(principal.getRole()).toString())
                .build();
    }
}