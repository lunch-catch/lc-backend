package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.AuthCookieFactory;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.OwnerLoginResponse;
import com.lunchcatch.owner.service.OwnerTokenRefreshResult;
import com.lunchcatch.owner.service.OwnerTokenRefreshService;
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
        var response = ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,
                cookieFactory.accessTokenCookie(result.accessToken(), Role.OWNER).toString());
        // 중복 갱신은 AT만 발급하고 다른 탭이 받은 새 RT 쿠키를 덮어쓰지 않는다.
        if (result.refreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE,
                    cookieFactory.refreshTokenCookie(result.refreshToken(), Role.OWNER, true).toString());
        }
        return response.body(ResponseEnvelope.success(result.response()));
    }
}
