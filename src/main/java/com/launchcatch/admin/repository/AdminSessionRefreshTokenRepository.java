package com.launchcatch.admin.repository;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.launchcatch.auth.opaque.RefreshTokenRepository.RotateOutcome;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

// 소유자 ID는 관리자 ID가 아닌 세션 PK이며 기존 계정 토큰과 키 공간을 분리한다.
@Repository
public class AdminSessionRefreshTokenRepository {
    private final RefreshTokenRepository delegate;

    public AdminSessionRefreshTokenRepository(StringRedisTemplate redis) {
        delegate = RefreshTokenRepository.scoped(redis, "adminSession");
    }

    public Optional<RefreshTokenData> find(String token) { return delegate.find(token); }
    public RotateOutcome compareAndRotate(String oldToken, String newToken, Duration ttl) {
        return delegate.compareAndRotate(oldToken, newToken, ttl);
    }
    public boolean saveIfNewer(String token, Long sessionId, Role role, Duration ttl, long version) {
        return delegate.saveIfNewer(token, sessionId, role, true, ttl, version);
    }
    public void revokeBeforeVersion(Long sessionId, Role role, long version) {
        delegate.revokeBeforeVersion(sessionId, role, version);
    }
    public void revokeIfActiveHashMatches(String hash, Role role, Long sessionId) {
        delegate.revokeIfActiveHashMatches(hash, role, sessionId);
    }
    public boolean rollbackRotation(String oldHash, String newHash, Role role, Long sessionId) {
        return delegate.rollbackRotation(oldHash, newHash, role, sessionId);
    }
}
