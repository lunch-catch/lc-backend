package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.OwnerStoreOnboardingResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreOnboardingQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreOnboardingService {
    private final OwnerRepository owners;
    private final StoreOnboardingQuery stores;

    @Transactional(readOnly = true)
    public OwnerStoreOnboardingResponse getProgress(Long ownerId) {
        var status = owners.findStatusById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (status != OwnerStatus.ONBOARDING && status != OwnerStatus.ACTIVE) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        return OwnerStoreOnboardingResponse.from(stores.getProgress(ownerId));
    }
}