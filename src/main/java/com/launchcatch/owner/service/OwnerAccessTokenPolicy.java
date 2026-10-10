package com.launchcatch.owner.service;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.AccessTokenPolicy;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.owner.repository.OwnerRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAccessTokenPolicy implements AccessTokenPolicy {
    private static final String VERSION_CLAIM = "issuanceVersion";
    private final OwnerRepository owners;
    private final OwnerAccessTokenVersionRepository versions;

    @Override
    public Role role() { return Role.OWNER; }

    @Override
    public Map<String, Object> additionalClaims(Long subjectId) {
        // 로그인·재발급의 행 잠금과 같은 트랜잭션에서 현재 순번을 읽는다.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("점주 AT는 토큰 변경 트랜잭션 안에서 발급해야 한다");
        }
        long version = owners.findIssuanceVersionById(subjectId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (version < 0) {
            throw new IllegalStateException("점주 AT 발급 순번은 음수일 수 없다");
        }
        // 큰 long 값도 정밀도를 잃지 않도록 문자열로 서명한다.
        return Map.of(VERSION_CLAIM, Long.toString(version));
    }

    @Override
    public boolean isValid(Long subjectId, Map<String, Object> claims) {
        long version = issuanceVersion(claims.get(VERSION_CLAIM));
        if (version < 0) {
            return false;
        }
        try {
            return versions.isValid(subjectId, version);
        } catch (DataAccessException e) {
            // 공통 AT 검증의 기존 LENIENT 정책과 동일하게 Redis 조회 장애만 허용한다.
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
