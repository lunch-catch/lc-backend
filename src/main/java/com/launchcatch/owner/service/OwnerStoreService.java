package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.OwnerStoreResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerStoreService {
    private final OwnerRepository owners;
    private final StoreQueryService stores;

    public OwnerStoreResponse getMyStore(Long ownerId) {
        var owner = owners.findById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ONBOARDING && owner.getStatus() != OwnerStatus.ACTIVE) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        return OwnerStoreResponse.from(stores.getOwnerStoreOverview(ownerId));
    }
}
