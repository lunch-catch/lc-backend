package com.lunchcatch.store.service;

import com.lunchcatch.store.contract.StoreOnboardingQuery;
import com.lunchcatch.store.contract.StoreOnboardingStatus;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.repository.StoreOnboardingQueryRepository;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreRegistrationProgressQueryService implements StoreOnboardingQuery {
    private final StoreOnboardingQueryRepository stores;

    @Override
    @Transactional(readOnly = true)
    public StoreOnboardingStatus getProgress(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        var row = stores.findByOwnerId(ownerId);
        var readiness = row.readiness();
        var steps = Arrays.stream(StoreRegistrationStep.values())
                .map(step -> new StoreOnboardingStatus.Step(step.name(), step != StoreRegistrationStep.MENU,
                        readiness.completed(step)))
                .toList();
        return new StoreOnboardingStatus(row.storeId(),
                row.finalized() ? null : readiness.firstIncompleteStep().name(), row.version(),
                row.finalized(), !row.finalized() && readiness.canFinalize(), steps);
    }
}
