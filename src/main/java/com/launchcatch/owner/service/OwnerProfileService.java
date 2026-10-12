package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.OwnerProfileResponse;
import com.launchcatch.owner.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerProfileService {
    private final OwnerRepository ownerRepository;

    public OwnerProfileResponse getMyProfile(Long ownerId) {
        // 현재 점주 상태와 튜토리얼 확인 여부를 반환하기 위해 캐시 대신 DB를 직접 조회한다.
        var owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        return new OwnerProfileResponse(owner.getEmail(), owner.getStatus(), owner.isTutorialViewed());
    }
}