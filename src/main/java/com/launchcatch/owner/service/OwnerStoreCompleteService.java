package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.owner.dto.StoreCompleteRequest;
import com.launchcatch.owner.dto.StoreCompleteResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreCompleter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreCompleteService {
    private final OwnerRepository owners;
    private final StoreCompleter stores;

    @Transactional
    public StoreCompleteResponse complete(Long ownerId, StoreCompleteRequest request) {
        if (ownerId == null || ownerId <= 0 || request == null || request.version() == null || request.version() < 0) {
            throw new IllegalArgumentException("유효한 점주 ID와 버전이 필요합니다.");
        }
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ONBOARDING) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        var result = stores.complete(ownerId, request.version());
        return new StoreCompleteResponse(result.storeId(), true, result.finalizedAt().atZone(ClockConfig.ZONE).toOffsetDateTime(),
                owner.getStatus().name(), result.version());
    }
}
