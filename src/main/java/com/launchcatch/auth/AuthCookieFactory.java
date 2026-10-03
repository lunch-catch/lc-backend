package com.launchcatch.auth;

import com.launchcatch.auth.jwt.JwtTokenProvider;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/*
 * Access 와 Refresh 쿠키를 만들고 지우는 자리를 한 곳에 모은다.
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
     * Refresh 는 재발급과 로그아웃 경로에만 실린다. 14일(관리자는 1일)짜리 토큰이라
     * 모든 요청에 실으면 접근 로그와 프록시에 남는 면이 넓어진다. 쿠키 이름이 하나뿐이라
     * 범위를 겹치게 두면 한 브라우저에서 역할을 바꿔 로그인할 때 서로를 덮어쓴다.
     *
     * 소비자가 셋이라 경로도 셋이다(docs/api-spec/auth.md 의 공통 토큰 정책). 옮겨온 쪽은
     * 회원과 관리자 둘이어서 경로가 둘이었다.
     *
     * 끝의 슬래시를 붙여 둔다. RFC 6265 5.1.4 의 path-match 는 경로가 같거나, 쿠키 path 가
     * 슬래시로 끝나거나, 요청 path 의 다음 글자가 슬래시여야 성립한다. 지금 경로는
     * `/v1/auth/refresh` 처럼 슬래시로 이어지므로 셋 다 성립하지만, 뒤에 콜론 커스텀 메서드를
     * 쓰는 경로가 생기면 슬래시로 끝나는 쪽만 남는다.
     *
     * 이 범위는 보안 경계가 아니다. 같은 오리진 안에서는 격리를 보장하지 않는다.
     * 실제 방어는 HttpOnly 와 SameSite=Strict 이고 이것은 노출 면을 줄이는 층 하나다.
     */
    private static final String MEMBER_REFRESH_COOKIE_PATH = "/v1/auth/";
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
     * 사용자가 자동 로그인을 체크하지 않은 경우가 그렇다. 브라우저를 닫으면 함께 사라진다.
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
            case MEMBER -> MEMBER_REFRESH_COOKIE_PATH;
        };
    }
}
