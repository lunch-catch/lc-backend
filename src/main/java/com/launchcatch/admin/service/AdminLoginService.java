package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.dto.AdminLoginResponse;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AdminLoginService {
    private static final int BCRYPT_MAX_BYTES = 72;
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminLoginTransactionService loginTransactionService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AdminLoginService(
            AdminRepository adminRepository, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
            AdminLoginTransactionService loginTransactionService,
            RefreshTokenRepository refreshTokenRepository, Clock clock) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.loginTransactionService = loginTransactionService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-admin-login");
    }

    @Transactional(propagation = Propagation.NEVER)
    public AdminLoginResult login(AdminLoginRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Admin> found = adminRepository.findByLoginId(request.loginId());
        String hash = found.map(Admin::getPasswordHash).orElse(dummyPasswordHash);
        boolean tooLong = request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
        /*
         * 계정이 없어도 같은 인코더로 BCrypt 비교를 수행해 계정 존재 여부를 숨긴다.
         * 72바이트 초과 입력은 인코더에 전달하지 않되 더미 비교를 수행한 후 같은 실패로 처리한다.
         */
        boolean matches = passwordEncoder.matches(tooLong ? "invalid-overlong-password" : request.password(), hash);
        if (found.isEmpty() || tooLong || !matches || !found.get().isActive() || !found.get().getRole().isAdmin()) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }

        Admin admin = found.get();
        String refreshToken = OpaqueTokenGenerator.generate();
        String tokenHash = TokenHasher.sha256(refreshToken);
        Duration ttl = Duration.ofMillis(jwtTokenProvider.refreshTokenValidityMs(admin.getRole()));
        LocalDateTime now = LocalDateTime.now(clock);
        AdminLoginTransactionService.LoginDbState state;
        try {
            state = loginTransactionService.issueRefreshToken(admin.getId(), hash, tokenHash, now.plus(ttl));
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }

        String accessToken;
        try {
            accessToken = jwtTokenProvider.createAccessToken(state.adminId(), state.role());
        } catch (RuntimeException e) {
            cleanupFailedLogin(state.adminId(), tokenHash, now, e);
            throw e;
        }

        try {
            if (!loginTransactionService.isRefreshTokenCurrent(
                    state.adminId(), state.role(), refreshToken)) {
                log.info("event=ADMIN_LOGIN_CACHE_PUBLICATION_SKIPPED adminId={} reason=SUPERSEDED",
                        state.adminId());
            } else {
                publishRefreshToken(state, refreshToken, ttl);
            }
        } catch (AuthException e) {
            cleanupFailedLogin(state.adminId(), tokenHash, now, e);
            throw e;
        } catch (DataAccessException | TransactionException e) {
            AuthException failure = new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
            cleanupFailedLogin(state.adminId(), tokenHash, now, failure);
            throw failure;
        }
        return new AdminLoginResult(new AdminLoginResponse(state.adminId(), state.name(), state.role()),
                accessToken, refreshToken);
    }

    // DB 연결과 행 잠금을 반환한 뒤 순번 비교 Lua로 늦게 도착한 이전 게시를 거부한다.
    private void publishRefreshToken(AdminLoginTransactionService.LoginDbState state, String refreshToken, Duration ttl) {
        try {
            if (!refreshTokenRepository.saveIfNewer(refreshToken, state.adminId(), state.role(), true,
                    ttl, state.issuanceVersion())) {
                log.info("event=ADMIN_LOGIN_CACHE_PUBLICATION_SKIPPED adminId={} reason=NEWER_PUBLICATION",
                        state.adminId());
            }
        } catch (DataAccessException e) {
            // 예외 메시지와 cause는 토큰을 포함할 수 있어 종류와 스택 프레임만 남긴다.
            log.warn("event=ADMIN_LOGIN_CACHE_PUBLICATION_FAILED adminId={} errorType={} stack={}",
                    state.adminId(), e.getClass().getSimpleName(), java.util.Arrays.toString(e.getStackTrace()));
        }
    }

    // 다른 로그인의 백업을 지우지 않고 실패한 이번 로그인에서 기록한 해시만 조건부 제거한다.
    private void cleanupFailedLogin(Long adminId, String tokenHash, LocalDateTime now, RuntimeException failure) {
        try {
            loginTransactionService.clearRefreshTokenIfMatches(adminId, tokenHash, now);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
