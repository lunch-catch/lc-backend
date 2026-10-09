package com.launchcatch.owner.service;

import com.launchcatch.owner.dto.OwnerLoginResponse;

/*
 * 서버 내부 전달용이다.
 * 컨트롤러는 response만 본문에 담고 토큰은 쿠키로 전달한다.
 */
public record OwnerLoginResult(OwnerLoginResponse response, String accessToken, String refreshToken) {
    @Override
    public String toString() {
        return "OwnerLoginResult[REDACTED]";
    }
}