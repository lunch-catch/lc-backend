package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.OwnerTutorialResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerTutorialService {
    private final OwnerRepository owners;

    @Transactional(timeout = 5)
    public OwnerTutorialResponse complete(Long ownerId) {
        // 로그인·재발급과 같은 점주 행을 잠가 현재 상태 확인과 저장을 보호한다.
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ACTIVE) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        // 여러 번 요청해도 tutorialViewed는 true로 유지되며 성공 응답을 반환한다.
        owner.completeTutorial();
        owners.saveAndFlush(owner);
        return new OwnerTutorialResponse(owner.isTutorialViewed());
    }
}