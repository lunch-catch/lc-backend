package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.OwnerTermsAgreementResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.OwnerTermsAgreementRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerTermsSubmissionService {
    private final OwnerRepository owners;
    private final OwnerTermsAgreementRecorder terms;

    @Transactional(timeout = 5)
    public OwnerTermsAgreementResponse submit(Long ownerId) {
        // 기존 로그인·재발급·로그아웃과 같은 행 잠금을 약관 저장 트랜잭션 종료까지 유지한다.
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (owner.getStatus() != OwnerStatus.ONBOARDING) {
            throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        }
        var result = terms.recordAgreements(ownerId);
        return new OwnerTermsAgreementResponse(result.requiredTermsCompleted(), result.version(), result.resumeStep());
    }
}