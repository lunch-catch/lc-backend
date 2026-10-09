package com.launchcatch.owner.service;

import com.launchcatch.owner.dto.OwnerLoginResponse;

// 토큰은 컨트롤러에서 쿠키로만 전달한다.
public record OwnerTokenRefreshResult(OwnerLoginResponse response, String accessToken, String refreshToken) {
    @Override
    public String toString() {
        return "OwnerTokenRefreshResult[REDACTED]";
    }
}
