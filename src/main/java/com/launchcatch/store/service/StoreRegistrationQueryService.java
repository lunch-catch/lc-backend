package com.launchcatch.store.service;

import com.launchcatch.store.contract.StoreRegistrationQuery;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreRegistrationQueryService implements StoreRegistrationQuery {
    private final StoreRegistrationProgressRepository progresses;

    @Override
    public String findResumeStep(Long ownerId) {
        return progresses.findByOwnerId(ownerId)
                .map(progress -> progress.getResumeStep().name())
                .orElse(StoreRegistrationStep.TERMS.name());
    }
}