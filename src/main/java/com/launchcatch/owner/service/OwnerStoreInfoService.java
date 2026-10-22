package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.StoreInfoRequest;
import com.launchcatch.owner.dto.StoreInfoResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreInfoSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreInfoService {
    private final OwnerRepository owners;
    private final StoreInfoSaver stores;

    @Transactional
    public StoreInfoResponse save(Long ownerId, StoreInfoRequest request) {
        if (ownerId == null || ownerId <= 0 || request == null) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청이 필요합니다.");
        }
        requireOnboarding(ownerId);
        return StoreInfoResponse.from(stores.save(ownerId, request.toCommand()));
    }

    // 저장 중 점주 상태가 바뀌지 않도록 점주 행을 잠그고, 입점 신청 중인 점주만 허용한다.
    private void requireOnboarding(Long ownerId) {
        var owner = owners.findByIdForLogin(ownerId).orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ONBOARDING) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
    }
}
