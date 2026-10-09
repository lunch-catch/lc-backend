package com.launchcatch.admin.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import java.time.Duration;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogoutService {
    private final AdminLogoutTransactionService transactions;
    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenValidAfterRepository accessTokens;
    private final JwtTokenProvider jwt;

    @Transactional(propagation = Propagation.NEVER)
    public void logout(Long adminId, Role role) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(role, "role");
        if (!role.isAdmin()) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }
        try {
            var state = transactions.revokeRefreshToken(adminId, role);
            /*
             * 폐기 순번은 DB 해시가 이미 없는 재요청도 정리하고 지연된 이전 게시를 막는다.
             * 이미 더 최신 로그인이 게시됐다면 그 상태를 보존한다.
             */
            refreshTokens.revokeBeforeVersion(adminId, role, state.version());
            if (state.hash() != null) {
                refreshTokens.revokeIfActiveHashMatches(state.hash(), role, adminId);
            }
            accessTokens.invalidateBefore(role, adminId, state.cutoff(),
                    Duration.ofMillis(jwt.getAccessTokenValidityMs()));
        } catch (DataAccessException | TransactionException e) {
            // 저장소 예외 메시지와 cause에 자격증명이 포함될 수 있어 외부 예외를 연결하지 않는다.
            log.error("event=ADMIN_LOGOUT_FAILED adminId={} role={} errorType={} stack={}",
                    adminId, role, e.getClass().getSimpleName(), java.util.Arrays.toString(e.getStackTrace()));
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE);
        }
        try {
            transactions.recordSuccess(adminId);
        } catch (RuntimeException e) {
            log.error("event=ADMIN_LOGOUT_AUDIT_FAILED adminId={} errorType={} stack={}",
                    adminId, e.getClass().getSimpleName(), java.util.Arrays.toString(e.getStackTrace()));
        }
        log.info("event=ADMIN_LOGOUT success=true adminId={} role={}", adminId, role);
    }
}
