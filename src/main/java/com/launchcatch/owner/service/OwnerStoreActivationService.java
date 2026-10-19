package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreActivationService {
    private final OwnerRepository owners;
    private final OwnerStatusCacheInvalidationService status;

    @Transactional(propagation = Propagation.MANDATORY)
    public void activate(Long ownerId) {
        // 최종 등록 진입점에서 이미 점주 행을 잠갔으며 동일 트랜잭션에서 재진입한다.
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        owner.activate();
        status.invalidateAfterCommit(ownerId);
    }
}
