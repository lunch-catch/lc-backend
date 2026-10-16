package com.launchcatch.store.service;

import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주가 지금 자신의 가게 등록 진행 상태(재개 단계, 버전)를 조회한다(#375). 다음 단계
 * 완료 요청에 실어 보낼 버전을 얻는 자리라, 아직 없으면 initialize로 만들어 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class StoreRegistrationProgressQueryService {

    private final StoreRegistrationProgressService progressService;
    private final OwnerStatusQuery ownerStatusQuery;

    @Transactional
    public StoreRegistrationProgressResponse getProgress(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ONBOARDING) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        var snapshot = progressService.initialize(ownerId);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }
}
