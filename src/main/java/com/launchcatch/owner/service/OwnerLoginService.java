package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.opaque.OpaqueTokenGenerator;
import com.launchcatch.owner.dto.OwnerLoginRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.repository.OwnerRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OwnerLoginService {
    private final OwnerRepository owners;
    private final PasswordEncoder passwords;
    private final OwnerSessionService sessions;
    private final TransactionTemplate transactions;
    private final String dummyPasswordHash;

    public OwnerLoginService(OwnerRepository owners, PasswordEncoder passwords, OwnerSessionService sessions,
                             PlatformTransactionManager manager) {
        this.owners = owners;
        this.passwords = passwords;
        this.sessions = sessions;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
        dummyPasswordHash = passwords.encode(OpaqueTokenGenerator.generate());
    }

    public OwnerLoginResult login(OwnerLoginRequest request) {
        Owner candidate = owners.findByEmail(request.email()).orElse(null);
        String hash = candidate == null ? dummyPasswordHash : candidate.getPasswordHash();
        // BCrypt 연산 중에는 DB 연결과 행 잠금을 점유하지 않는다.
        boolean matches = passwords.matches(request.password(), hash);
        if (candidate == null || !matches || !candidate.canLogin()) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }
        try {
            OwnerLoginResult result = transactions.execute(tx -> {
                Owner owner = owners.findByIdForLogin(candidate.getId())
                        .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_FAILED));
                if (!owner.canLogin() || !hash.equals(owner.getPasswordHash())) {
                    throw new AuthException(AuthErrorCode.LOGIN_FAILED);
                }
                OwnerLoginResult issued = sessions.issue(owner);
                owners.saveAndFlush(owner);
                return issued;
            });
            sessions.publishIssued(result);
            return result;
        } catch (DataAccessException | TransactionException e) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, e);
        }
    }
}
