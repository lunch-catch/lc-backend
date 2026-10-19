package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.StoreHoursRequest;
import com.launchcatch.owner.dto.StoreHoursResponse;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreHoursSaver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerStoreHoursService {
    private final OwnerRepository owners;
    private final StoreHoursSaver hours;

    @Transactional
    public StoreHoursResponse save(Long ownerId, StoreHoursRequest request) {
        if (ownerId == null || ownerId <= 0 || request == null) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청이 필요합니다.");
        }
        // 점주 상태 변경과 영업시간 저장을 직렬화하며 점주 → 진행 상태 → 업무 데이터 순서로 잠근다.
        var owner = owners.findByIdForLogin(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        var result = switch (owner.getStatus()) {
            case ONBOARDING -> hours.saveOnboarding(ownerId, request.toCommand());
            case ACTIVE -> hours.saveActive(ownerId, request.toCommand());
            default -> throw new OwnerException(OwnerErrorCode.STATUS_NOT_ALLOWED);
        };
        return StoreHoursResponse.from(result);
    }
}
