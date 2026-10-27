package com.lunchcatch.admin;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import java.util.Map;

// 관리자 발급 순번의 클레임 이름과 자료형은 관리자 모듈이 소유한다.
public final class AdminAccessTokenClaims {
    private static final String ISSUANCE_VERSION = "issuanceVersion";

    private AdminAccessTokenClaims() { }

    public static Map<String, Object> forVersion(long version) {
        if (version <= 0) { throw new IllegalArgumentException("관리자 발급 순번은 양수여야 한다"); }
        return Map.of(ISSUANCE_VERSION, version);
    }

    public static Long issuanceVersion(Map<String, Object> claims) {
        if (!claims.containsKey(ISSUANCE_VERSION)) { return null; }
        Object value = claims.get(ISSUANCE_VERSION);
        if (value instanceof Integer number) { return number.longValue(); }
        if (value instanceof Long number) { return number; }
        throw new AuthException(AuthErrorCode.LOGIN_REQUIRED);
    }
}
