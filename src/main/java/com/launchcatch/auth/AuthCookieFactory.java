package com.launchcatch.auth;

import com.launchcatch.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/*
 * Access 와 Refresh 쿠키를 만들고 지우는 자리를 한 곳에 모은다 (94행).
 *
 * 두 토큰 모두 HttpOnly, SameSite=Strict 쿠키로 내려가고 응답 본문에는 토큰 문자열을 싣지
 * 않는다. 본문에 실으면 그 응답을 읽는 스크립트가 HttpOnly 여부와 무관하게 토큰을 그대로
 * 얻어가므로 HttpOnly 로 얻는 XSS 방어가 사라진다.
 *
 * 대신 Access 도 쿠키인 이상 CSRF 노출 범위가 인증이 필요한 전체 API 로 넓어진다.
 * SameSite=Strict 가 대부분을 막지만 완전한 방어는 아니다. CSRF 토큰 도입은 아직 정하지 않았다.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieFactory {

    private static final String ACCESS_TOKEN_COOKIE_NAME = "accessToken";
    private static final String REFRESH_TOKEN_COOKIE_NAME = "refreshToken";
    private static final String SAME_SITE_STRICT = "Strict";

    /** Access 는 인증이 필요한 모든 요청에 실려야 하므로 범위를 좁히지 않는다. */
    private static final String ACCESS_TOKEN_COOKIE_PATH = "/";

    /*
     * Refresh 는 재발급과 로그아웃 경로에만 실린다.
     *
     * 소비자가 셋이라 경로도 셋이다(docs/api-spec/README.md 의 경로 절). 옮겨온 쪽은 회원과 관리자
     * 둘이어서 경로가 둘이었다.
     *
     * 끝의 슬래시가 중요하다. RFC 6265 5.1.4 의 path-match 는 경로가 같거나, 쿠키 path 가
     * 슬래시로 끝나거나, 요청 path 의 다음 글자가 슬래시여야 성립한다. 재발급이
     * `/v1/auth/tokens:refresh` 처럼 콜론 커스텀 메서드라 다음 글자가 콜론이어서, path 를
     * `/v1/auth/tokens` 로 좁히면 `:refresh` 요청에는 쿠키가 실리지 않는다.
     */
    private static final String USER_REFRESH_COOKIE_PATH = "/v1/auth/";
    private static final String OWNER_REFRESH_COOKIE_PATH = "/v1/owner/auth/";
    private static final String ADMIN_REFRESH_COOKIE_PATH = "/v1/admin/auth/";

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.cookie.secure:false}")
    private boolean secure;

    public ResponseCookie accessTokenCookie(String accessToken) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, accessToken)
                .httpOnly(true)
                .secure(secure)
                .path(ACCESS_TOKEN_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .maxAge(Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()))
                .build();
    }

    public ResponseCookie expiredAccessTokenCookie() {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path(ACCESS_TOKEN_COOKIE_PATH)
                .sameSite(SAME_SITE_STRICT)
                .maxAge(Duration.ZERO)
                .build();
    }

    /*
     * persistent 가 false 면 maxAge 를 주지 않아 세션 쿠키가 된다.
     * 사용자가 자동 로그인을 체크하지 않은 경우가 그렇다. 브라우저를 닫으면 함께 사라진다(64행).
     */
    public ResponseCookie refreshTokenCookie(String refreshToken, Role role, boolean persistent) {
        ResponseCookie.ResponseCookieBuilder builder =
                ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, refreshToken)
                        .httpOnly(true)
                        .secure(secure)
                        .path(refreshCookiePath(role))
                        .sameSite(SAME_SITE_STRICT);
        if (persistent) {
            builder.maxAge(Duration.ofMillis(jwtTokenProvider.refreshTokenValidityMs(role)));
        }
        return builder.build();
    }

    public ResponseCookie expiredRefreshTokenCookie(Role role) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path(refreshCookiePath(role))
                .sameSite(SAME_SITE_STRICT)
                .maxAge(Duration.ZERO)
                .build();
    }

    private String refreshCookiePath(Role role) {
        return switch (role) {
            case SUPER_ADMIN, ADMIN -> ADMIN_REFRESH_COOKIE_PATH;
            case OWNER -> OWNER_REFRESH_COOKIE_PATH;
            case USER -> USER_REFRESH_COOKIE_PATH;
        };
    }
}
