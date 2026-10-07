package com.launchcatch.member.oauth;

import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

public record KakaoIdentity(String providerUserId, String nickname, String profileImageUrl) {

    public static KakaoIdentity from(Jwt idToken) {
        Map<String, Object> claims = idToken.getClaims();
        String subject = idToken.getSubject();
        String nickname = claimText(claims, "nickname");
        if (subject == null || subject.isBlank() || nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("Kakao ID token has required claim missing");
        }
        return new KakaoIdentity(subject, nickname, claimText(claims, "picture"));
    }

    private static String claimText(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value instanceof String text ? text : null;
    }
}
