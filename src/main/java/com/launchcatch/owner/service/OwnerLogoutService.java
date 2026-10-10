package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerAccessTokenVersionRepository;
import com.launchcatch.owner.repository.OwnerLogoutTokenRepository;
import com.launchcatch.owner.repository.OwnerRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OwnerLogoutService {
    private final OwnerRepository owners;
    private final OwnerLogoutTokenRepository tokens;
    private final OwnerAccessTokenVersionRepository cutoff;
    private final TransactionTemplate transactions;

    public OwnerLogoutService(OwnerRepository owners, OwnerLogoutTokenRepository tokens,
            OwnerAccessTokenVersionRepository cutoff,
            PlatformTransactionManager manager) {
        this.owners = owners;
        this.tokens = tokens;
        this.cutoff = cutoff;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
    }

    public void logout(Long ownerId) {
        Revocation revocation;
        try {
            revocation = transactions.execute(tx -> {
                // 로그인·재발급과 같이 점주 행을 잠가 한 번에 하나의 요청만 토큰 정보를 변경하게 한다.
                var owner = owners.findByIdForLogin(ownerId)
                        .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
                // DB에서 지우기 전에 Redis에서도 삭제할 기존 RT 해시를 보관한다.
                String hash = owner.getRefreshTokenHash();
                owner.clearRefreshToken();
                owners.saveAndFlush(owner);
                return new Revocation(hash, owner.getRefreshTokenIssuanceVersion());
            });
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }

        /*
         * DB 커밋이 성공한 뒤 Redis 폐기를 시작한다.
         * RT 폐기가 실패해도 AT 차단은 시도하며, 하나라도 실패하면 503을 반환한다.
         */
        DataAccessException failure = null;
        try {
            tokens.revokeThroughVersion(ownerId, revocation.hash(), revocation.version());
        } catch (DataAccessException e) {
            failure = e;
        }
        try {
            // 시각 대신 DB에서 확정한 폐기 순번까지의 AT를 차단한다.
            cutoff.invalidateThroughVersion(ownerId, revocation.version());
        } catch (DataAccessException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, failure);
        }
    }

    private record Revocation(String hash, long version) { }
}
