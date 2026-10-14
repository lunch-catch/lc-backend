package com.launchcatch.owner.controller;

import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.owner.service.OwnerLogoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerLogoutController {
    private final OwnerLogoutService service;
    private final AuthCookieFactory cookies;

    @DeleteMapping("/v1/owner/auth/tokens")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails user) {
        service.logout(user.getId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cookies.expiredAccessTokenCookie().toString(),
                cookies.expiredRefreshTokenCookie(Role.OWNER).toString()).build();
    }
}
