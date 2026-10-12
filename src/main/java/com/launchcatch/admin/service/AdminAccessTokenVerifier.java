package com.launchcatch.admin.service;

import com.launchcatch.admin.repository.AdminAccessTokenVersionRepository;
import com.launchcatch.admin.repository.AdminAccessTokenRevocationRepository;
import com.launchcatch.admin.AdminAccessTokenClaims;
import java.util.Map;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.contract.AccessTokenValidationPolicy;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenCutoffVerifier;
import com.launchcatch.auth.jwt.CutoffPolicy;
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
        if (!isValidInDatabase(id, issuedAt, version, policy)) { return false; }
        // Redis는 이전 배포와의 호환을 위한 추가 검사이며 DB에서 폐기된 토큰을 허용하지 못한다.
        if (version == null) { return legacyCutoff.isValidAfter(role, id, issuedAt, policy); }
        try {
            return versions.isVersionValid(role, id, version);
        } catch (DataAccessException failure) {
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
            }
            log.warn("event=ADMIN_ACCESS_TOKEN_VERSION_CHECK_FAILED adminId={} role={} errorType={}",
                    id, role, failure.getClass().getSimpleName());
            return true;
        }
    }

    private boolean isValidInDatabase(Long id, LocalDateTime issuedAt, Long version, CutoffPolicy policy) {
        try {
            var state = revocations.findRevocation(id);
            if (state.isEmpty()) { return false; }
            if (version != null) { return version > state.get().version(); }
            return state.get().validAfter() == null || !issuedAt.isBefore(state.get().validAfter());
        } catch (DataAccessException | TransactionException failure) {
            // DB 기준을 확인하지 못하면 Redis 장애 허용 정책과 관계없이 관리자 인증을 거부한다.
            log.warn("event=ADMIN_ACCESS_TOKEN_DB_CHECK_FAILED adminId={} errorType={}",
                    id, failure.getClass().getSimpleName());
            if (policy == CutoffPolicy.REQUIRED) {
                throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
            }
            return false;
        }
    }

}
