package com.launchcatch.admin.service;

import com.launchcatch.admin.repository.AdminAccessTokenVersionRepository;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAccessTokenVerifier implements AccessTokenValidationPolicy {
    private final AdminAccessTokenVersionRepository versions;
    private final AccessTokenCutoffVerifier legacyCutoff;

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
        // 배포 전에 발급한 JWT는 기존 폐기 시각으로 검증한다.
        if (version == null) { return legacyCutoff.isValidAfter(role, id, issuedAt, policy); }
        if (version <= 0) { return false; }
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
}
