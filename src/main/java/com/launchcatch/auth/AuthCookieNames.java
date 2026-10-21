package com.launchcatch.auth;

// 인증 쿠키를 발급하는 쪽과 읽는 쪽이 같은 이름을 사용한다.
public final class AuthCookieNames {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "accessToken";

    private AuthCookieNames() {
    }
}
