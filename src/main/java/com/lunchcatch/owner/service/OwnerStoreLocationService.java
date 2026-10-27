package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.StoreLocationRequest;
import com.lunchcatch.owner.dto.StoreLocationResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.StoreLocationSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreLocationService {
    private final OwnerRepository owners;
    private final StoreLocationSaver locations;

    @Transactional
    public StoreLocationResponse save(Long ownerId, StoreLocationRequest request) {
        if (ownerId == null || ownerId <= 0 || request == null) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청이 필요합니다.");
        }
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ONBOARDING) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        return StoreLocationResponse.from(locations.save(ownerId, request.toCommand()));
    }
}
