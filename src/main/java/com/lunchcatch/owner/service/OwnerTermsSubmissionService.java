package com.lunchcatch.owner.service;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.owner.dto.OwnerTermsAgreementResponse;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.exception.OwnerErrorCode;
import com.lunchcatch.owner.exception.OwnerException;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.store.contract.OwnerTermsAgreementRecorder;
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