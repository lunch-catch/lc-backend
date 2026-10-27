package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.OwnerProfileResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.store.contract.StoreRegistrationQuery;
import com.lunchcatch.owner.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerProfileService {
    private final OwnerRepository ownerRepository;
    private final StoreRegistrationQuery registrationQuery;

    public OwnerProfileResponse getMyProfile(Long ownerId) {
        // 현재 점주 상태와 튜토리얼 확인 여부를 반환하기 위해 캐시 대신 DB를 직접 조회한다.
        var owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        String registrationStep = owner.getStatus() == OwnerStatus.ONBOARDING
                ? registrationQuery.findResumeStep(ownerId) : null;
        return new OwnerProfileResponse(owner.getEmail(), owner.getStatus(), owner.isTutorialViewed(), registrationStep);
    }
}