package com.lunchcatch.owner.service;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.contract.AccessTokenValidationPolicy;
import com.lunchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import com.lunchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.lunchcatch.owner.repository.OwnerAccessTokenRevocationRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAccessTokenPolicy implements AccessTokenValidationPolicy {
    private static final String VERSION_CLAIM = "issuanceVersion";
    private final OwnerSessionService sessions;
    private final OwnerAccessTokenVersionRepository versions;
    private final OwnerAccessTokenRevocationRepository revocations;

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
        if (claims.containsKey(OwnerSessionService.SESSION_CLAIM)) {
            try {
                return sessions.isValid(subjectId, claims.get(OwnerSessionService.SESSION_CLAIM), policy);
            } catch (DataAccessException | TransactionException e) {
                /*
                 * 세션을 확인하지 못한 토큰은 일반 요청에서도 통과시키지 않고 저장소 장애로 답한다(#1226).
                 * 무효로 답하면 클라이언트가 재발급을 불러 DB 부하를 늘리고 장애가 토큰 만료에 섞인다.
                 */
                log.warn("event=OWNER_SESSION_ACCESS_CHECK_FAILED ownerId={} exceptionType={}", subjectId, e.getClass().getSimpleName());
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
            }
        }
        long version = issuanceVersion(claims.get(VERSION_CLAIM));
        if (version < 0) {
            return false;
        }
        try {
            var revokedVersion = revocations.findRevokedVersion(subjectId);
            if (revokedVersion.isEmpty() || version <= revokedVersion.get()) {
                return false;
            }
        } catch (DataAccessException | TransactionException e) {
            // 폐기 순번을 읽지 못하면 세션 확인과 같은 이유로 일반 요청도 저장소 장애로 답한다(#1226)
            log.warn("event=OWNER_ACCESS_TOKEN_DB_CHECK_FAILED ownerId={} exceptionType={}", subjectId, e.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        }
        try {
            return versions.isValid(subjectId, version);
        } catch (DataAccessException e) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
            }
            // DB에서 폐기되지 않은 AT만 일반 인증의 Redis 조회 장애를 허용한다.
            log.warn("event=OWNER_ACCESS_TOKEN_VERSION_CHECK_FAILED ownerId={} policy=LENIENT exceptionType={} fallback=ALLOW", subjectId, e.getClass().getSimpleName());
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
