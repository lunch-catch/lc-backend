package com.launchcatch.store.service;

import com.launchcatch.store.BusinessVerificationStatus;
import com.launchcatch.store.StoreBasicInfo;
import com.launchcatch.store.StoreLocation;
import com.launchcatch.store.contract.StoreLocationSaveCommand;
import com.launchcatch.store.contract.StoreLocationSaveResult;
import com.launchcatch.store.contract.StoreLocationSaver;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import com.launchcatch.store.repository.StoreRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreLocationService implements StoreLocationSaver {
    private final StoreRegistrationProgressRepository progresses;
    private final StoreRepository stores;
    private final OwnerTermsAgreementRepository agreements;
    private final StoreRegistrationProgressService registration;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StoreLocationSaveResult save(Long ownerId, StoreLocationSaveCommand command) {
        if (ownerId == null || ownerId <= 0 || command == null || command.version() == null || command.version() < 0) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청 버전이 필요합니다.");
        }
        var location = new StoreLocation(command.kakaoPlaceId(), command.roadAddress(), command.latitude(), command.longitude());
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (progress.getVersion() != command.version()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        if (progress.getResumeStep().ordinal() < StoreRegistrationStep.LOCATION.ordinal()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        var store = stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        if (!Objects.equals(store.getId(), progress.getStoreId()) || store.isFinalized()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        }
        var agreed = agreements.findByOwnerId(ownerId)
                .filter(value -> value.isAgreed() && value.getAgreedAt() != null && value.getWithdrawnAt() == null)
                .isPresent();
        if (!agreed || store.getBusinessVerificationStatus() != BusinessVerificationStatus.VERIFIED
                || store.getBusinessVerifiedAt() == null) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        new StoreBasicInfo(store.getName(), store.getCategoryCode(), store.getRepresentativeName(),
                store.getBusinessRegistrationNumber(), store.getPhone());
        store.replaceLocation(location);
        stores.saveAndFlush(store);
        var saved = progress.getResumeStep() == StoreRegistrationStep.LOCATION
                ? registration.completeStep(ownerId, command.version(), StoreRegistrationStep.LOCATION, null)
                : registration.reviseStep(ownerId, command.version(), StoreRegistrationStep.LOCATION);
        return new StoreLocationSaveResult(saved.storeId(), saved.resumeStep().name(), saved.version());
    }
}
