package com.launchcatch.owner.controller;

import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.Role;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.service.OwnerTokenRefreshResult;
import com.launchcatch.owner.service.OwnerTokenRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerTokenRefreshController {
    private final OwnerTokenRefreshService service;
    private final AuthCookieFactory cookieFactory;

    @PostMapping("/v1/owner/auth/tokens:refresh")
    public ResponseEntity<ResponseEnvelope<OwnerLoginResponse>> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {
        OwnerTokenRefreshResult result = service.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.accessTokenCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE,
                        cookieFactory.refreshTokenCookie(result.refreshToken(), Role.OWNER, true).toString())
                .body(ResponseEnvelope.success(result.response()));
    }
}
