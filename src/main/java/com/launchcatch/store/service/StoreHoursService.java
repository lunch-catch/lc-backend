package com.launchcatch.store.service;

import com.launchcatch.store.StoreBusinessHours;
import com.launchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import java.util.List;
import com.launchcatch.store.contract.StoreHoursSaveCommand;
import com.launchcatch.store.contract.StoreHoursSaveResult;
import com.launchcatch.store.contract.StoreHoursSaver;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreBusinessHourRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class StoreHoursService implements StoreHoursSaver {
    private final StoreRegistrationProgressRepository progresses;
    private final OwnerStoreQueryRepository stores;
    private final StoreBusinessHourRepository hours;
    private final StoreRegistrationProgressService registration;
    private final Clock clock;

    @Override
    public StoreHoursSaveResult saveOnboarding(Long ownerId, StoreHoursSaveCommand command) {
        var businessHours = validate(ownerId, command);
        if (command.version() == null || command.version() < 0) {
            throw new IllegalArgumentException("ONBOARDING 저장에는 0 이상의 version이 필요합니다.");
        }
        var progress = progresses.findByOwnerIdForUpdate(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_PROGRESS_NOT_FOUND));
        if (progress.getVersion() != command.version()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        if (progress.getResumeStep().ordinal() < StoreRegistrationStep.HOURS.ordinal()) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        var store = stores.findByOwnerId(ownerId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        if (store.finalized() || !Objects.equals(store.storeId(), progress.getStoreId())) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED);
        }
        var agreement = stores.findTermsAgreement(ownerId).orElse(null);
        if (agreement == null || !agreement.agreed() || agreement.withdrawnAt() != null
                || !present(store.name()) || !present(store.businessCategory()) || !present(store.representativeName())
                || !present(store.businessRegistrationNumber()) || !present(store.phone())
                || !"VERIFIED".equals(store.businessVerificationStatus()) || store.businessVerifiedAt() == null
                || !present(store.kakaoPlaceId()) || !present(store.roadAddress())
                || store.latitude() == null || store.longitude() == null) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        hours.replace(store.storeId(), businessHours, LocalDateTime.now(clock));
        var saved = progress.getResumeStep() == StoreRegistrationStep.HOURS
                ? registration.completeStep(ownerId, command.version(), StoreRegistrationStep.HOURS, null)
                : registration.reviseStep(ownerId, command.version(), StoreRegistrationStep.HOURS);
        return new StoreHoursSaveResult(saved.storeId(), saved.resumeStep().name(), saved.version());
    }

    @Override
    public StoreHoursSaveResult saveActive(Long ownerId, StoreHoursSaveCommand command) {
        var businessHours = validate(ownerId, command);
        if (command.version() != null) {
            throw new IllegalArgumentException("ACTIVE 저장에는 온보딩 version을 보내지 않습니다.");
        }
        var store = stores.findByOwnerId(ownerId)
                .filter(value -> value.finalized())
                .orElseThrow(() -> new StoreException(StoreErrorCode.REGISTRATION_STORE_NOT_ALLOWED));
        hours.replace(store.storeId(), businessHours, LocalDateTime.now(clock));
        return new StoreHoursSaveResult(store.storeId(), null, null);
    }

    private static List<BusinessHour> validate(Long ownerId, StoreHoursSaveCommand command) {
        if (ownerId == null || ownerId <= 0 || command == null) {
            throw new IllegalArgumentException("유효한 점주 ID와 요청이 필요합니다.");
        }
        return StoreBusinessHours.normalize(command.businessHours());
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
