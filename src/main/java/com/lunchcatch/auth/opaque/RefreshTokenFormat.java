package com.lunchcatch.auth.opaque;

// 공용 저장소와 점주 저장소가 같은 토큰 레코드와 보조 키를 사용한다.
public final class RefreshTokenFormat {

    public static final String KEY_PREFIX = "refreshToken:";
    public static final String ACTIVE_KEY_PREFIX = "activeRefreshToken:";
    public static final String ISSUANCE_VERSION_KEY_PREFIX = "refreshTokenIssuanceVersion:";
    public static final String REVOKED_SUFFIX = "|REVOKED";

    private RefreshTokenFormat() {
    }
}
