package com.lunchcatch.admin.service;

import com.lunchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.lunchcatch.admin.repository.AdminAccessTokenRevocationRepository;
import com.lunchcatch.admin.AdminAccessTokenClaims;
import java.util.Map;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.contract.AccessTokenValidationPolicy;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.lunchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccessTokenVerifier implements AccessTokenValidationPolicy {
    private final AdminAccessTokenVersionRepository versions;
    private final AccessTokenCutoffVerifier legacyCutoff;
    private final AdminAccessTokenRevocationRepository revocations;
    private final AdminSessionService sessions;

    @Override
    public boolean supports(Role role) { return role != null && role.isAdmin(); }

    @Override
    public boolean isValid(Role role, Long id, LocalDateTime issuedAt, Map<String, Object> claims, CutoffPolicy policy) {
        Long version;
        try {
            version = AdminAccessTokenClaims.issuanceVersion(claims);
        } catch (AuthException malformedClaim) {
            return false;
        }
        return isValidAfter(role, id, issuedAt, version, policy);
    }

    public boolean isValidAfter(Role role, Long id, LocalDateTime issuedAt, Long version, CutoffPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (!supports(role) || issuedAt == null) { return false; }
        if (id == null || (version != null && version <= 0)) { return false; }
        if (version != null) {
            // 세션 저장소 장애(AUTH-002)는 일반 요청에서도 그대로 올려 503 으로 답한다(#1226)
            var session = sessions.isValid(id, role, version);
            if (session.isPresent()) { return session.get(); }
        }
        if (!isValidInDatabase(id, issuedAt, version)) { return false; }
        // Redis는 이전 배포와의 호환을 위한 추가 검사이며 DB에서 폐기된 토큰을 허용하지 못한다.
        if (version == null) { return legacyCutoff.isValidAfter(role, id, issuedAt, policy); }
        try {
            return versions.isVersionValid(role, id, version);
        } catch (DataAccessException failure) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
            }
            log.warn("event=ADMIN_ACCESS_TOKEN_VERSION_CHECK_FAILED adminId={} role={} exceptionType={}",
                    id, role, failure.getClass().getSimpleName());
            return true;
        }
    }

    private boolean isValidInDatabase(Long id, LocalDateTime issuedAt, Long version) {
        try {
            var state = revocations.findRevocation(id);
            if (state.isEmpty()) { return false; }
            if (version != null) { return version > state.get().version(); }
            return state.get().validAfter() == null || !issuedAt.isBefore(state.get().validAfter());
        } catch (DataAccessException | TransactionException failure) {
            /*
             * DB 기준을 확인하지 못하면 Redis 장애 허용 정책과 관계없이 관리자 인증을 거부한다.
             * 일반 요청도 무효(401)가 아니라 저장소 장애(503)로 답해 재발급을 부르지 않게 한다(#1226).
             */
            log.warn("event=ADMIN_ACCESS_TOKEN_DB_CHECK_FAILED adminId={} exceptionType={}",
                    id, failure.getClass().getSimpleName());
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        }
    }

}
