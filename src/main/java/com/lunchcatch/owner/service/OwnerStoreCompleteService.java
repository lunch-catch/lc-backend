package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.global.config.ClockConfig;
import com.lunchcatch.owner.dto.StoreCompleteRequest;
import com.lunchcatch.owner.dto.StoreCompleteResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreCompleter;
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
