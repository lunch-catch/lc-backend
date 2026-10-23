package com.launchcatch.store.service;

import com.launchcatch.global.logging.TransactionLogging;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
                .orElseThrow(() -> rejected(ownerId, StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (progress.getVersion() != expectedVersion) {
            throw rejected(ownerId, StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        var store = stores.findByOwnerId(ownerId)
                .filter(value -> !value.isFinalized() && Objects.equals(value.getId(), progress.getStoreId()))
                .orElseThrow(() -> rejected(ownerId, StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        if (progress.getResumeStep() != StoreRegistrationStep.MENU
                || !onboarding.findByOwnerId(ownerId).readiness().canFinalize()) {
            throw rejected(ownerId, StoreErrorCode.REGISTRATION_REQUIREMENTS_NOT_MET);
        }
        Long storeId = store.getId();
        TransactionLogging.afterFailure(() -> log.error(
                "event=STORE_REGISTRATION_FAILED ownerId={} storeId={} reason=TRANSACTION_NOT_COMMITTED",
                ownerId, storeId));
        progress.reviseStep(expectedVersion, StoreRegistrationStep.MENU);
        store.completeRegistration(LocalDateTime.now(clock));
        stores.saveAndFlush(store);
        // 동기 이벤트 수신 실패도 가게 확정과 진행 상태 변경을 함께 롤백한다.
        events.publishEvent(new StoreFinalizedEvent(ownerId, store.getId()));
        TransactionLogging.afterCommit(() -> log.info(
                "event=STORE_REGISTRATION_SUCCESS ownerId={} storeId={}", ownerId, storeId));
        return new StoreCompleteResult(store.getId(), store.getFinalizedAt(), progress.getVersion());
    }

    private StoreException rejected(Long ownerId, StoreErrorCode errorCode) {
        log.info("event=STORE_REGISTRATION_FAILED ownerId={} reason=REJECTED errorCode={}",
                ownerId, errorCode.getCode());
        return new StoreException(errorCode);
    }
}
