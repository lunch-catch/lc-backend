package com.launchcatch.auth.contract;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import java.util.Map;

/*
 * 도메인은 토큰 검증 규칙을 구현해 빈으로 등록한다.
 * auth는 이 인터페이스로 호출하므로 도메인 내부 코드를 알 필요가 없다.
 */
public interface AccessTokenValidationPolicy {
    // 이 정책이 검증할 역할인지 알려준다.
    boolean supports(Role role);

    // 공통 JWT 검증을 통과한 토큰에 도메인 규칙을 적용하며 추가 클레임은 도메인이 해석한다.
    boolean isValid(Role role, Long id, LocalDateTime issuedAt, Map<String, Object> additionalClaims, CutoffPolicy policy);
}
