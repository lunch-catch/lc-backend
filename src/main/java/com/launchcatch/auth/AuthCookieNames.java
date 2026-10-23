package com.launchcatch.auth;

/*
 * 인증 쿠키를 발급하는 쪽과 읽는 쪽이 같은 이름을 사용한다.
 * Access Token 쿠키는 역할마다 이름이 다르다. 하나를 같이 쓰면 같은 브라우저에서 나중에 로그인한 역할의 토큰이
 * 앞 역할의 토큰을 덮어써 다른 역할의 화면이 403 이 된다(#841).
 */
public final class AuthCookieNames {

    public static final String ADMIN_ACCESS_TOKEN_COOKIE_NAME = "adminAccessToken";
    public static final String OWNER_ACCESS_TOKEN_COOKIE_NAME = "ownerAccessToken";

    // 회원 앱의 기존 세션이 이어지도록 회원은 역할이 하나이던 때의 이름을 그대로 쓴다
    public static final String MEMBER_ACCESS_TOKEN_COOKIE_NAME = "accessToken";

    private static final String ADMIN_PATH = "/v1/admin";
    private static final String OWNER_PATH = "/v1/owner";

    private AuthCookieNames() {
    }

    public static String accessTokenCookieName(Role role) {
        return switch (role) {
            case SUPER_ADMIN, ADMIN -> ADMIN_ACCESS_TOKEN_COOKIE_NAME;
            case OWNER -> OWNER_ACCESS_TOKEN_COOKIE_NAME;
            case MEMBER -> MEMBER_ACCESS_TOKEN_COOKIE_NAME;
        };
    }

    /*
     * 요청 경로로 읽을 Access Token 쿠키를 고른다. /v1/admin 아래는 관리자만, /v1/owner 아래는 점주만 쓰고
     * 나머지 인증 경로는 회원만 쓴다. 점주 가입(/v1/owners)처럼 접두사만 같은 경로는 회원 쪽으로 간다.
     */
    public static String accessTokenCookieNameForPath(String path) {
        if (isUnder(path, ADMIN_PATH)) {
            return ADMIN_ACCESS_TOKEN_COOKIE_NAME;
        }
        if (isUnder(path, OWNER_PATH)) {
            return OWNER_ACCESS_TOKEN_COOKIE_NAME;
        }
        return MEMBER_ACCESS_TOKEN_COOKIE_NAME;
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
