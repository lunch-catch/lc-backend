package com.launchcatch.store.service;

import com.launchcatch.store.contract.StoreInfoSaver;
import com.launchcatch.store.contract.StoreInfoSaveCommand;
import com.launchcatch.store.contract.StoreInfoSaveResult;
import com.launchcatch.store.StoreBasicInfo;
import com.launchcatch.store.BusinessVerificationStatus;
import com.launchcatch.store.MockBusinessVerifier;
import com.launchcatch.store.entity.Store;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import com.launchcatch.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
public class StoreInfoService implements StoreInfoSaver {
    private final StoreRegistrationProgressRepository progresses;
    private final StoreRepository stores;
    private final StoreRegistrationProgressService registration;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreInfoSaveResult save(Long ownerId, StoreInfoSaveCommand command) {
        if (ownerId == null || ownerId <= 0 || command == null || command.version() == null || command.version() < 0) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청 버전이 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (progress.getVersion() != command.version()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        if (progress.getResumeStep() == StoreRegistrationStep.TERMS) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        var info = new StoreBasicInfo(command.name(), command.categoryCode(), command.representativeName(),
                command.businessRegistrationNumber(), command.phone());
        var existing = stores.findByOwnerId(ownerId);
        if ((progress.getStoreId() == null && existing.isPresent())
                || (progress.getStoreId() != null && (existing.isEmpty()
                || !Objects.equals(existing.get().getId(), progress.getStoreId())))
                || existing.filter(Store::isFinalized).isPresent()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        }
        var verification = MockBusinessVerifier.verify(info.businessRegistrationNumber());
        if (verification == BusinessVerificationStatus.FAILED) {
            return new StoreInfoSaveResult(progress.getStoreId(), progress.getResumeStep().name(), progress.getVersion(), verification.name());
        }
        var now = LocalDateTime.now(clock);
        if (progress.getResumeStep() == StoreRegistrationStep.STORE_INFO) {
            var store = stores.saveAndFlush(Store.createDraft(ownerId, info, now));
            var saved = registration.completeStep(ownerId, command.version(), StoreRegistrationStep.STORE_INFO, store.getId());
            return new StoreInfoSaveResult(saved.storeId(), saved.resumeStep().name(), saved.version(), verification.name());
        }
        var store = existing.orElseThrow();
        store.replaceVerifiedBasicInfo(info, now);
        stores.saveAndFlush(store);
        var saved = registration.reviseStep(ownerId, command.version(), StoreRegistrationStep.STORE_INFO);
        return new StoreInfoSaveResult(saved.storeId(), saved.resumeStep().name(), saved.version(), verification.name());
    }
}
