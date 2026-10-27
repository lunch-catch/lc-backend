package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.AuthCookieFactory;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.owner.service.OwnerLogoutService;
import com.lunchcatch.owner.service.OwnerSessionService;
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
    private final OwnerSessionService sessions;
    private final AuthCookieFactory cookies;

    @DeleteMapping("/v1/owner/auth/tokens")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails user) {
        if (user.getAdditionalClaims().containsKey(OwnerSessionService.SESSION_CLAIM)) {
            sessions.logout(user.getId(), (String) user.getAdditionalClaims().get(
                    OwnerSessionService.SESSION_CLAIM));
        } else {
            service.logout(user.getId());
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                cookies.expiredAccessTokenCookie(Role.OWNER).toString(),
                cookies.expiredRefreshTokenCookie(Role.OWNER).toString()).build();
    }
}
