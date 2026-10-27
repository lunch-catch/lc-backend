package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.OwnerStoreResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreQueryService;
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
