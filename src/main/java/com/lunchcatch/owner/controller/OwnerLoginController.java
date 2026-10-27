package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.AuthCookieFactory;
import com.lunchcatch.auth.Role;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.OwnerLoginRequest;
import com.lunchcatch.owner.dto.OwnerLoginResponse;
import com.lunchcatch.owner.service.OwnerLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerLoginController {
    private final OwnerLoginService ownerLoginService;
    private final AuthCookieFactory authCookieFactory;

    @PostMapping("/v1/owner/auth/tokens")
    public ResponseEntity<ResponseEnvelope<OwnerLoginResponse>> login(
            @Valid @RequestBody OwnerLoginRequest request) {
        var result = ownerLoginService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        authCookieFactory.accessTokenCookie(result.accessToken(), Role.OWNER).toString(),
                        authCookieFactory.refreshTokenCookie(result.refreshToken(), Role.OWNER, true).toString())
                .body(ResponseEnvelope.success(result.response()));
    }
}