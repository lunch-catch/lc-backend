package com.launchcatch.admin.dto;

// 토큰 원문은 서비스와 컨트롤러 사이에서 쿠키 생성에만 사용한다.
public record AdminLoginResult(AdminLoginResponse response, String accessToken, String refreshToken) {
    @Override
    public String toString() {
        return "AdminLoginResult[response=" + response + ", accessToken=****, refreshToken=****]";
    }
}
