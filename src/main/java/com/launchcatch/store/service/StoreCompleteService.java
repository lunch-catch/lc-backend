package com.launchcatch.store.service;

import com.launchcatch.store.contract.StoreCompleter;
import com.launchcatch.store.contract.StoreCompleteResult;
import com.launchcatch.store.contract.StoreFinalizedEvent;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreOnboardingQueryRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import com.launchcatch.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StoreCompleteService implements StoreCompleter {
    private final StoreRegistrationProgressRepository progresses;
    private final StoreRepository stores;
    private final StoreOnboardingQueryRepository onboarding;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public StoreCompleteResult complete(Long ownerId, long expectedVersion) {
        if (ownerId == null || ownerId <= 0 || expectedVersion < 0) {
            throw new IllegalArgumentException("유효한 점주 ID와 버전이 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (progress.getVersion() != expectedVersion) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        var store = stores.findByOwnerId(ownerId)
                .filter(value -> !value.isFinalized() && Objects.equals(value.getId(), progress.getStoreId()))
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        if (progress.getResumeStep() != StoreRegistrationStep.MENU
                || !onboarding.findByOwnerId(ownerId).readiness().canFinalize()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_REQUIREMENTS_NOT_MET);
        }
        progress.reviseStep(expectedVersion, StoreRegistrationStep.MENU);
        store.completeRegistration(LocalDateTime.now(clock));
        stores.saveAndFlush(store);
        // 동기 이벤트 수신 실패도 가게 확정과 진행 상태 변경을 함께 롤백한다.
        events.publishEvent(new StoreFinalizedEvent(ownerId, store.getId()));
        return new StoreCompleteResult(store.getId(), store.getFinalizedAt(), progress.getVersion());
    }
}
