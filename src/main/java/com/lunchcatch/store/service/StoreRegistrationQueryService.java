package com.lunchcatch.store.service;

import com.lunchcatch.store.contract.StoreRegistrationQuery;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.repository.StoreRegistrationProgressRepository;
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