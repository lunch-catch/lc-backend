package com.launchcatch.owner.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.dto.OwnerLoginResponse;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class OwnerLoginService {
    private final OwnerRepository ownerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final String dummyPasswordHash;

    public OwnerLoginService(OwnerRepository ownerRepository,
                             PasswordEncoder passwordEncoder,
                             JwtTokenProvider jwtTokenProvider, RefreshTokenRepository refreshTokenRepository,
                             Clock clock, PlatformTransactionManager transactionManager) {
        this.ownerRepository = ownerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 계정 존재 여부와 무관하게 동일한 인코더로 비교 연산을 수행한다.
        this.dummyPasswordHash = passwordEncoder.encode(OpaqueTokenGenerator.generate());
    }

    public OwnerLoginResult login(OwnerLoginRequest request) {
        Owner candidate = ownerRepository.findByEmail(request.email()).orElse(null);
        String hash = candidate == null ? dummyPasswordHash : candidate.getPasswordHash();
        // BCrypt 비교는 저장 트랜잭션 밖에서 수행한다.
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (candidate == null || !matches || !candidate.canLogin()) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }

        String refreshToken = OpaqueTokenGenerator.generate();
        Duration ttl = Duration.ofMillis(jwtTokenProvider.refreshTokenValidityMs(Role.OWNER));
        OwnerLoginResult result;
        try {
            result = transactionTemplate.execute(transaction -> {
                // 비교 이후 변경된 상태를 다시 확인하고 동시 로그인 저장을 직렬화한다.
                Owner owner = ownerRepository.findByIdForLogin(candidate.getId())
                        .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
                if (!owner.canLogin() || !hash.equals(owner.getPasswordHash())) {
                    throw new AuthException(AuthErrorCode.LOGIN_FAILED);
                }
                LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ClockConfig.ZONE);
                owner.recordLogin(TokenHasher.sha256(refreshToken), now.plus(ttl), now);
                ownerRepository.saveAndFlush(owner);
                String accessToken = jwtTokenProvider.createAccessToken(
                        owner.getId(), Role.OWNER, owner.getStatus().name());
                Boolean tutorialViewed = owner.getStatus() == OwnerStatus.ACTIVE
                        ? owner.isTutorialViewed() : null;
                return new OwnerLoginResult(new OwnerLoginResponse(owner.getEmail(), Role.OWNER,
                        owner.getStatus(), tutorialViewed), accessToken, refreshToken);
            });
        } catch (DataAccessException | TransactionException e) {
            /*
             * 커밋 실패도 포함한다.
             * DB 저장이 확정되기 전에는 쿠키나 캐시를 발급하지 않는다.
             */
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
        try {
            refreshTokenRepository.save(refreshToken, candidate.getId(), Role.OWNER, true, ttl);
        } catch (DataAccessException e) {
            // 예외 메시지에 토큰 또는 DB/캐시 값이 포함될 수 있어 상세를 로그에 넣지 않는다.
            log.warn("event=OWNER_LOGIN_REFRESH_CACHE_FAILED DB 저장을 기준으로 로그인을 유지합니다.");
        }
        return result;
    }
}