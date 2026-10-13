package com.launchcatch.owner.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.contract.AccessTokenValidationPolicy;
import com.launchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAccessTokenPolicy implements AccessTokenValidationPolicy {
    private static final String VERSION_CLAIM = "issuanceVersion";
    private final OwnerAccessTokenVersionRepository versions;

    @Override
    public boolean supports(Role role) { return role == Role.OWNER; }

    public static Map<String, Object> additionalClaims(long version) {
        if (version < 0) {
            throw new IllegalStateException("점주 AT 발급 순번은 음수일 수 없다");
        }
        // 큰 long 값도 정밀도를 잃지 않도록 문자열로 서명한다.
        return Map.of(VERSION_CLAIM, Long.toString(version));
    }

    @Override
    public boolean isValid(Role role, Long subjectId, LocalDateTime issuedAt, Map<String, Object> claims, CutoffPolicy policy) {
        if (!supports(role) || subjectId == null || issuedAt == null) { return false; }
        long version = issuanceVersion(claims.get(VERSION_CLAIM));
        if (version < 0) {
            return false;
        }
        try {
            return versions.isValid(subjectId, version);
        } catch (DataAccessException e) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
            }
            // 일반 인증은 Redis 조회 장애를 허용한다.
            log.warn("event=OWNER_ACCESS_TOKEN_VERSION_CHECK_FAILED ownerId={} policy=LENIENT", subjectId);
            return true;
        }
    }

    // 순번이 없던 기존 JWT도 최초 로그아웃부터 차단할 수 있도록 0으로 취급한다.
    private long issuanceVersion(Object value) {
        if (value == null) {
            return 0L;
        }
        if (!(value instanceof String text) || !text.matches("[0-9]{1,19}")) {
            return -1L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
