package com.launchcatch.auth;

/*
 * 역할 넷이 단일 축이다 (기능 명세서 94행).
 *
 * 옮겨온 쪽은 회원과 관리자를 TokenType 으로 가르고 그 안에서 role 을 또 봤다. 트랙이 둘이라
 * 토큰마다 type 과 role 을 함께 실었다. 런치캐치는 계정을 셋으로 나눈 대신 역할을 하나의
 * enum 으로 두고 그것만 본다. 관리자, 점주, 사용자가 서로 다른 도메인에 있어 식별자 공간이
 * 겹치지 않고, 인가 판정은 역할 하나로 끝난다.
 *
 * SUPER_ADMIN 과 ADMIN 을 나눈 것은 관리자 계정 발급을 최고 관리자만 할 수 있기 때문이다(4행).
 */
public enum Role {

    SUPER_ADMIN,
    ADMIN,
    OWNER,
    MEMBER;

    /** Spring Security 가 비교하는 권한 문자열이다. 저장과 전송에는 name() 을 쓴다. */
    public String toAuthority() {
        return "ROLE_" + name();
    }

    /*
     * 위조되거나 예전 판의 토큰이 목록에 없는 값을 담고 있으면 null 을 돌려준다.
     * 호출부(JwtAuthenticationFilter)가 null 이면 인증을 건너뛰는 경로를 이미 갖고 있어
     * 거기로 흡수시킨다. 예외를 던지면 필터 체인 밖으로 새어 GlobalExceptionHandler 를
     * 거치지 못한 비정형 500 이 된다.
     */
    public static Role from(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isAdmin() {
        return this == ADMIN || this == SUPER_ADMIN;
    }
}
