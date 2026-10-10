package com.launchcatch.auth.jwt;

import com.launchcatch.auth.Role;
import java.util.Map;

// 각 도메인이 JWT에 추가할 정보와 인증 시 확인할 조건을 정의하는 공통 인터페이스다.
public interface AccessTokenPolicy {
    // 이 정책을 적용할 계정 역할을 반환한다.
    Role role();
    // 토큰 발급 시 JWT에 함께 넣을 도메인별 추가 정보를 반환한다.
    Map<String, Object> additionalClaims(Long subjectId);
    // JWT에 담긴 정보가 해당 도메인의 인증 조건을 만족하는지 확인한다.
    boolean isValid(Long subjectId, Map<String, Object> claims);
}
